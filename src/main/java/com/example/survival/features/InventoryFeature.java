package com.example.survival.features;

import com.example.survival.SurvivalPlugin;
import com.example.survival.features.ActionManager;
import com.example.survival.utils.ConfigManager;
import com.example.survival.utils.ItemTranslator;
import xin.bbtt.MovementSync;
import xin.bbtt.inventory.InventoryManager;
import xin.bbtt.inventory.ItemRegistry;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.movements.ActionMovement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.cloudburstmc.math.vector.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class InventoryFeature {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    /** 经验瓶物品ID */
    private static final int XP_BOTTLE_ID = 759; // EXPERIENCE_BOTTLE

    private volatile boolean enabled = false;
    private ScheduledExecutorService scheduler;

    // 一次性警报（防止重复刷屏）
    private final Set<String> alertedItems = ConcurrentHashMap.newKeySet();
    private final Set<String> alertedMending = ConcurrentHashMap.newKeySet();
    // 数量充足一次性日志（每个槽位只报告一次，除非数量再次降到阈值以下）
    private final Set<Integer> reportedSufficientSlots = ConcurrentHashMap.newKeySet();
    // 装备升级警报（防止重复刷屏，每个装备槽只提示一次）
    private final Set<Integer> alertedBetterArmor = ConcurrentHashMap.newKeySet();

    // 时间控制
    private volatile long lastAutoDropTime = 0;
    private volatile long lastRefillTime = 0;
    private volatile long lastMendingTime = 0;
    private volatile long lastHotbarSwitchTime = 0;
    private volatile long lastDurabilityCheckTime = 0;
    private volatile long lastBetterArmorCheckTime = 0;
    private volatile long lastTotemPollTime = 0;
    private static final long TOTEM_POLL_INTERVAL = 10_000; // 每10秒轮询
    private static final String TOTEM_NAME = "totem_of_undying";
    /** 记录最近一次图腾操作时间，防止重复触发 */
    private volatile long lastTotemActionTime = 0;
    private static final long TOTEM_ACTION_COOLDOWN = 3000; // 3秒冷却

    // 记录原手持（用于经验修补归还）
    private int savedHotbarSlot = -1;

    // Bug-1 Fix: Instant refill triggered from PacketListener when hotbar item < 5
    /** Instant refill for a specific hotbar slot (called from PacketListener) */
    public void triggerInstantRefill(int hotbarIndex) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        // Debounce: only allow one instant refill per hotbar slot every 2 seconds
        if (now - lastInstantRefillTime < 2000) return;
        lastInstantRefillTime = now;

        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return;

        int slot = 36 + hotbarIndex;
        if (slot >= inventory.length) return;
        ItemStack item = inventory[slot];
        if (item == null) return;

        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
        if (entry == null || entry.getStackSize() <= 1) return; // Skip non-stackable

        int currentCount = item.getAmount();
        if (currentCount >= 5) return; // Already above threshold

        // Search main inventory for same item type
        String targetName = entry.getName();
        for (int s = 9; s < 36; s++) {
            if (s >= inventory.length) continue;
            ItemStack candidate = inventory[s];
            if (candidate == null) continue;
            ItemRegistry.ItemEntry candEntry = ItemRegistry.Instance.getItem(candidate.getId());
            if (candEntry == null) continue;
            if (targetName.equals(candEntry.getName())) {
                if (shiftMoveToHotbar(s, hotbarIndex)) {
                    log.info("[InventoryFeature] Instant refill hotbar[{}]: {} x{} -> refilled",
                            hotbarIndex, targetName, currentCount);
                }
                break;
            }
        }
    }
    private volatile long lastInstantRefillTime = 0;

    public void enable() {
        if (enabled) return;
        enabled = true;
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "InventoryFeature-Tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 1000, 30000, TimeUnit.MILLISECONDS);
        log.info("[InventoryFeature] 已启动");
    }

    /**
     * 进入游戏获得 entityId 后调用，执行首次背包扫描 + 副手补充
     */
    public void onPlayerLogin() {
        log.info("[InventoryFeature] 登录后首次图腾检查...");
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] invData = inv != null ? inv.getInventory() : null;
        checkAndReplenishTotem(invData);
    }

    public void disable() {
        enabled = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        alertedItems.clear();
        alertedMending.clear();
        alertedBetterArmor.clear();
        log.info("[InventoryFeature] 已停止");
    }

    public boolean isEnabled() { return enabled; }

    private void tick() {
        if (!enabled) return;
        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        long now = System.currentTimeMillis();

        // 1. 耐久度警报（每30秒一次）
        if (now - lastDurabilityCheckTime >= 30_000) {
            checkDurability(cfg.getInventoryDurabilityThreshold());
            lastDurabilityCheckTime = now;
        }

        // 2. 自动丢弃
        long dropIntervalMs = cfg.getInventoryAutoDropInterval() * 1000L;
        if (now - lastAutoDropTime >= dropIntervalMs) {
            int dropped = doAutoDrop(cfg.getInventoryAutoDropItems());
            if (dropped > 0) log.info("[InventoryFeature] 自动丢弃了 {} 个物品", dropped);
            lastAutoDropTime = now;
        }

        // 3. 快捷栏补充
        if (cfg.isInventoryAutoRefillEnabled() && now - lastRefillTime >= 30000) {
            doAutoRefill(cfg);
            lastRefillTime = now;
        }

        // 4. 经验修补
        if (cfg.isInventoryMendingEnabled() && now - lastMendingTime >= 8000) {
            log.info("[InventoryFeature] 经验修补: 进行装备检查...");
            int repaired = doMending(cfg);
            if (repaired > 0) log.info("[InventoryFeature] 经验修补: 修复了 {} 件装备", repaired);
            lastMendingTime = now;
        }

        // 5. 快捷栏自动切换
        if (cfg.isInventoryAutoHotbarSwitchEnabled() && now - lastHotbarSwitchTime >= 2000) {
            doAutoHotbarSwitch(cfg);
            lastHotbarSwitchTime = now;
        }

        // 6. 更好的装备检测提示（每30秒一次）
        if (cfg.isBetterArmorAlertEnabled() && now - lastBetterArmorCheckTime >= 30_000) {
            checkBetterArmor(cfg);
            lastBetterArmorCheckTime = now;
        }

        // 7. 图腾轮询：每10秒检查一次副手图腾状态
        if (now - lastTotemPollTime >= TOTEM_POLL_INTERVAL) {
            lastTotemPollTime = now;
            InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
            ItemStack[] invData = inv != null ? inv.getInventory() : null;
            checkAndReplenishTotem(invData);
        }
    }

    // ==================== 1. 耐久度警报 ====================

    private void checkDurability(int thresholdPercent) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return;

        int[] checkSlots = {5, 6, 7, 8, 36 + inv.getHeldSlot(), 45};
        String[] slotNames = {"头盔", "胸甲", "护腿", "靴子", "主手", "副手"};

        for (int i = 0; i < checkSlots.length; i++) {
            int slot = checkSlots[i];
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item == null) continue;

            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null) continue;
            String itemName = entry.getName();
            if (itemName == null) continue;

            int maxDur = getMaxDurability(itemName);
            if (maxDur <= 0) continue;

            int currentDur = getItemCurrentDurability(item, maxDur);
            if (currentDur < 0) continue;

            int percent = (currentDur * 100) / maxDur;
            if (percent <= thresholdPercent) {
                log.warn("[InventoryFeature] {} 耐久度过低: {}% ({}/{})",
                        slotNames[i], percent, currentDur, maxDur);
            }
        }
    }

    private int getItemCurrentDurability(ItemStack item, int maxDurability) {
        try {
            var dataComponents = item.getDataComponentsPatch();
            if (dataComponents == null) return -1;
            var components = dataComponents.getDataComponents();
            if (components == null || components.isEmpty()) return -1;
            for (var entry : components.entrySet()) {
                String keyStr = entry.getKey().getKey().toString();
                if (keyStr.contains("damage")) {
                    return maxDurability - ((Number) entry.getValue().getValue()).intValue();
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // ==================== 2. 自动丢弃 ====================

    private int doAutoDrop(List<String> dropList) {
        if (dropList == null || dropList.isEmpty()) return 0;
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return 0;

        int dropped = 0;
        for (int slot = 9; slot < inventory.length; slot++) {
            ItemStack item = inventory[slot];
            if (item == null) continue;

            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null) continue;
            String itemName = entry.getName();
            if (itemName == null) continue;
            itemName = itemName.toUpperCase();

            for (String dropItem : dropList) {
                if (itemName.contains(dropItem.toUpperCase()) || dropItem.toUpperCase().contains(itemName)) {
                    try {
                        dropItemAtSlot(slot);
                        dropped++;
                    } catch (Exception e) {
                        log.debug("[InventoryFeature] 丢弃失败: {}", e.getMessage());
                    }
                    break;
                }
            }
        }
        return dropped;
    }

    private void dropItemAtSlot(int slot) {
        var session = Bot.INSTANCE.getSession();
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();

        if (slot >= 36 && slot < 45) {
            int hotbarSlot = slot - 36;
            inv.switchToSlot(hotbarSlot);
            session.send(new ServerboundPlayerActionPacket(
                    PlayerAction.DROP_ITEM, Vector3i.ZERO, Direction.DOWN, 0));
            return;
        }

        // 主背包 → 换到快捷栏再丢弃
        int emptyHotbar = findEmptyHotbarSlot(getInventory());
        if (emptyHotbar < 0) emptyHotbar = 8;

        // 从背包槽移动到快捷栏槽
        if (moveToHotbar(slot, emptyHotbar)) {
            inv.switchToSlot(emptyHotbar);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            session.send(new ServerboundPlayerActionPacket(
                    PlayerAction.DROP_ITEM, Vector3i.ZERO, Direction.DOWN, 0));
        }
    }

    private ItemStack[] getInventory() {
        return MovementSync.INSTANCE.getInventoryManager().getInventory();
    }

    // ==================== 3. 快捷栏补充（少于阈值时补充）====================

    private void doAutoRefill(ConfigManager cfg) {
        int threshold = cfg.getInventoryAutoRefillThreshold();
        List<String> refillItems = cfg.getInventoryAutoRefillItems();
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return;

        int refilled = 0;
        for (int h = 0; h < 9; h++) {
            int hotbarSlot = 36 + h;
            if (hotbarSlot >= inventory.length) continue;
            ItemStack hotbarItem = inventory[hotbarSlot];

            if (hotbarItem == null) continue;

            // === 排除装备：仅补充可堆叠物品（stackSize > 1） ===
            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(hotbarItem.getId());
            if (entry == null) continue;
            if (entry.getStackSize() <= 1) {
                // 装备（工具/武器/盔甲等），跳过
                continue;
            }

            // 获取当前数量
            int currentCount;
            try {
                currentCount = hotbarItem.getAmount();
            } catch (Exception e) {
                currentCount = 64;
            }

            // === 获取物品名称（提前声明，供日志使用） ===
            String targetName = entry.getName();

            // === 数量 >= threshold 时跳过（仅首次输出一次日志） ===
            if (currentCount >= threshold) {
                if (!reportedSufficientSlots.contains(h)) {
                    log.info("[InventoryFeature] 快捷栏[{}] {} 数量充足(x{})，无需补充",
                            h, targetName, currentCount);
                    reportedSufficientSlots.add(h);
                }
                continue;
            }

            // 数量已不足，从已报告Set中移除，下次充足时重新报告
            reportedSufficientSlots.remove(h);

            // === 数量 < threshold，需要补充 ===
            boolean found = false;

            // 在主背包搜索同类物品
            for (int s = 9; s < 36; s++) {
                if (s >= inventory.length) continue;
                ItemStack candidate = inventory[s];
                if (candidate == null) continue;
                ItemRegistry.ItemEntry candEntry = ItemRegistry.Instance.getItem(candidate.getId());
                if (candEntry == null) continue;
                if (targetName.equals(candEntry.getName())) {
                    // 使用 MOVE_TO_HOTBAR_SLOT 方式补充
                    if (shiftMoveToHotbar(s, h)) {
                        log.info("[InventoryFeature] 补充快捷栏[{}]: {} x{} -> 充足",
                                h, targetName, currentCount);
                        refilled++;
                        found = true;
                    }
                    break;
                }
            }

            // 数量不足且背包里找不到可补充的物品 → 仅当物品在 alertItems 列表（金苹果/图腾）或配置文件指定时才输出警告
            if (!found) {
                if (isAlertItem(targetName, cfg)) {
                    log.warn("[InventoryFeature] 快捷栏[{}] {} 数量 x{} 不足，背包中未找到同类物品可补充！",
                            h, targetName, currentCount);
                }
            }
        }
        if (refilled > 0) {
            log.info("[InventoryFeature] 本次共补充了 {} 个槽位", refilled);
        }
    }

    /**
     * 判断物品是否在警告列表中（配置指定或默认金苹果/图腾）
     */
    private boolean isAlertItem(String itemName, ConfigManager cfg) {
        if (itemName == null) return false;
        // 默认永远警告的物品
        if (itemName.equals("golden_apple") || itemName.equals(TOTEM_NAME)) {
            return true;
        }
        // 检查配置的自定义警告列表
        List<String> alertItems = cfg.getInventoryAutoRefillAlertItems();
        if (alertItems != null) {
            for (String alert : alertItems) {
                if (alert != null) {
                    // 精确匹配物品名，或匹配数字ID
                    if (alert.equals(itemName) || alert.equals(String.valueOf(getItemIdByName(itemName)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 根据物品名查ID（仅用于配置匹配） */
    private int getItemIdByName(String name) {
        // 遍历注册表匹配名字
        try {
            java.lang.reflect.Field f = ItemRegistry.Instance.getClass().getDeclaredField("entries");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Integer, ItemRegistry.ItemEntry> map = (java.util.Map<Integer, ItemRegistry.ItemEntry>) f.get(ItemRegistry.Instance);
            for (ItemRegistry.ItemEntry e : map.values()) {
                if (e.getName().equals(name)) return e.getId();
            }
        } catch (Exception ex) { /* ignore */ }
        return -1;
    }

    // ==================== 4. 经验修补（只使用经验瓶）====================

    private int doMending(ConfigManager cfg) {
        int threshold = cfg.getInventoryMendingThreshold();
        log.info("[Mending] ========== 开始经验修补检查 阈值={}% ==========", threshold);
        
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) {
            log.info("[Mending] inventory 为 null，退出");
            return 0;
        }
        log.info("[Mending] inventory 长度={}", inventory.length);

        // 1. 找 XP 瓶
        log.info("[Mending] Step 1: 搜索经验瓶...");
        int xpHotbarSlot = findItemInHotbarByName("EXPERIENCE_BOTTLE");
        log.info("[Mending] 快捷栏搜索结果: xpHotbarSlot={}", xpHotbarSlot);
        
        if (xpHotbarSlot < 0) {
            int xpInvSlot = findItemInInventoryByName("EXPERIENCE_BOTTLE");
            log.info("[Mending] 背包搜索结果: xpInvSlot={}", xpInvSlot);
            
            if (xpInvSlot < 0) {
                log.info("[Mending] 没有找到经验瓶，退出");
                return 0;
            }
            
            int emptyHotbar = findEmptyHotbarSlot(inventory);
            log.info("[Mending] 空快捷栏槽位: emptyHotbar={}", emptyHotbar);
            if (emptyHotbar < 0) emptyHotbar = 8;
            
            log.info("[Mending] 尝试移动经验瓶从背包槽位 {} 到快捷栏槽位 {}...", xpInvSlot, emptyHotbar);
            boolean moved = moveToHotbar(xpInvSlot, emptyHotbar);
            log.info("[Mending] 移动结果: {}", moved);
            
            if (!moved) {
                log.info("[Mending] 无法移动经验瓶到快捷栏，退出");
                return 0;
            }
            xpHotbarSlot = emptyHotbar;
        }
        log.info("[Mending] 最终经验瓶位置: 快捷栏槽位 {} (协议槽位 {})", xpHotbarSlot, 36 + xpHotbarSlot);

        // 2. 找需要修补的装备
        log.info("[Mending] Step 2: 检查护甲槽位 (5-8)...");
        List<Integer> repairSlots = new ArrayList<>();
        
        for (int slot = 5; slot <= 8; slot++) {
            log.info("[Mending] --- 检查槽位 {} ---", slot);
            
            if (slot >= inventory.length) {
                log.info("[Mending] 槽位 {} >= inventory.length {}，跳过", slot, inventory.length);
                continue;
            }
            
            ItemStack item = inventory[slot];
            if (item == null) {
                log.info("[Mending] 槽位 {} 物品为 null，跳过", slot);
                continue;
            }
            
            log.info("[Mending] 槽位 {} 物品 itemId={}", slot, item.getId());
            
            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null) {
                log.info("[Mending] 槽位 {} 物品无注册信息，跳过", slot);
                continue;
            }
            
            String itemName = entry.getName();
            log.info("[Mending] 槽位 {} 物品名称: {}", slot, itemName);
            
            int maxDur = getMaxDurability(itemName);
            log.info("[Mending] {} 最大耐久: maxDur={}", itemName, maxDur);
            
            if (maxDur <= 0) {
                log.info("[Mending] {} maxDur <= 0，无法确定耐久，跳过", itemName);
                continue;
            }
            
            int currentDur = getItemCurrentDurability(item, maxDur);
            log.info("[Mending] {} 当前耐久: currentDur={}", itemName, currentDur);
            
            if (currentDur < 0) {
                log.info("[Mending] {} 无法解析当前耐久，跳过", itemName);
                continue;
            }
            
            int percent = (currentDur * 100) / maxDur;
            log.info("[Mending] {} 耐久百分比: {}% (阈值 {}%)", itemName, percent, threshold);
            
            if (percent <= threshold) {
                log.info("[Mending] {} {}% <= {}%，加入修补列表", itemName, percent, threshold);
                repairSlots.add(slot);
            } else {
                log.info("[Mending] {} {}% > {}%，无需修补", itemName, percent, threshold);
            }
        }

        // 检查主手
        int heldSlot = inv.getHeldSlot();
        int handSlot = 36 + heldSlot;
        log.info("[Mending] Step 3: 检查主手 heldSlot={} protocolSlot={}", heldSlot, handSlot);
        
        if (handSlot < inventory.length) {
            ItemStack hand = inventory[handSlot];
            if (hand == null) {
                log.info("[Mending] 主手为空");
            } else {
                log.info("[Mending] 主手物品 itemId={}", hand.getId());
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(hand.getId());
                if (entry == null) {
                    log.info("[Mending] 主手物品无注册信息");
                } else {
                    String itemName = entry.getName();
                    log.info("[Mending] 主手物品名称: {}", itemName);
                    
                    int maxDur = getMaxDurability(itemName);
                    log.info("[Mending] 主手 {} 最大耐久: maxDur={}", itemName, maxDur);
                    
                    if (maxDur > 0) {
                        int currentDur = getItemCurrentDurability(hand, maxDur);
                        log.info("[Mending] 主手 {} 当前耐久: currentDur={}", itemName, currentDur);
                        
                        if (currentDur >= 0) {
                            int percent = (currentDur * 100) / maxDur;
                            log.info("[Mending] 主手 {} 耐久百分比: {}% (阈值 {}%)", itemName, percent, threshold);
                            
                            if (percent <= threshold) {
                                log.info("[Mending] 主手 {} {}% <= {}%，加入修补列表", itemName, percent, threshold);
                                repairSlots.add(handSlot);
                            } else {
                                log.info("[Mending] 主手 {} {}% > {}%，无需修补", itemName, percent, threshold);
                            }
                        } else {
                            log.info("[Mending] 主手 {} 无法解析当前耐久", itemName);
                        }
                    } else {
                        log.info("[Mending] 主手 {} maxDur={} <= 0，跳过", itemName, maxDur);
                    }
                }
            }
        } else {
            log.info("[Mending] handSlot {} >= inventory.length {}，跳过", handSlot, inventory.length);
        }

        log.info("[Mending] Step 4: 修补列表结果 repairSlots={}", repairSlots);
        
        if (repairSlots.isEmpty()) {
            log.info("[Mending] 没有需要修补的装备，退出");
            return 0;
        }
        log.info("[Mending] 检测到 {} 件装备需要修补: {}", repairSlots.size(), repairSlots);

        // 3. 执行经验瓶使用
        final int xpSlot = xpHotbarSlot;
        final int originalSlot = heldSlot;
        log.info("[Mending] Step 5: 准备执行 ActionMovement xpSlot={} originalSlot={}", xpSlot, originalSlot);

        int[] completed = {0};
        MovementSync.INSTANCE.getMovementController().insertMovement(
            new ActionMovement(() -> {
                log.info("[Mending] >>> ActionMovement 开始执行");
                
                log.info("[Mending] >>> 切换到经验瓶槽位 {}", xpSlot);
                inv.switchToSlot(xpSlot);
                
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                
                log.info("[Mending] >>> 发送 UseItemPacket");
                Bot.INSTANCE.getSession().send(
                    new ServerboundUseItemPacket(Hand.MAIN_HAND, 0, 0f, 0f));
                
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                
                log.info("[Mending] >>> 发送 SwingPacket");
                Bot.INSTANCE.getSession().send(
                    new ServerboundSwingPacket(Hand.MAIN_HAND));
                
                log.info("[Mending] >>> 切换回原槽位 {}", originalSlot);
                inv.switchToSlot(originalSlot);
                
                log.info("[Mending] >>> ActionMovement 执行完成");
                synchronized (completed) {
                    completed[0] = 1;
                    completed.notify();
                }
            }, 0)
        );
        log.info("[Mending] ActionMovement 已插入，等待执行...");

        // 等待完成
        log.info("[Mending] Step 6: 等待 ActionMovement 完成（最多3秒）");
        synchronized (completed) {
            while (completed[0] == 0) {
                try { completed.wait(3000); } catch (InterruptedException ignored) { break; }
            }
        }
        log.info("[Mending] 等待结束 completed={}", completed[0]);

        // 4. 输出修补日志
        log.info("[Mending] Step 7: 输出修补结果");
        int repaired = 0;
        for (int repairSlot : repairSlots) {
            String key = "mending_" + repairSlot;
            if (alertedMending.contains(key)) {
                log.info("[Mending] 槽位 {} 已在 alertedMending 中，跳过日志", repairSlot);
                continue;
            }

            ItemStack item = inventory[repairSlot];
            ItemRegistry.ItemEntry entry = item != null ? ItemRegistry.Instance.getItem(item.getId()) : null;
            String itemName = entry != null ? entry.getName() : "unknown";
            log.info("[Mending] ✓ 对 {} (槽位{}) 使用经验瓶修补", itemName, repairSlot);
            alertedMending.add(key);
            repaired++;
        }
        
        log.info("[Mending] ========== 经验修补完成 repaired={} ==========", repaired);
        return repaired;
    }

    // ==================== 5. 快捷栏自动切换 ====================

    private void doAutoHotbarSwitch(ConfigManager cfg) {
        List<String> watchItems = cfg.getInventoryAutoHotbarSwitchItems();
        if (watchItems == null || watchItems.isEmpty()) return;

        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return;

        int currentHeld = inv.getHeldSlot();

        // 检查当前手持是否在监控列表
        int currentItemId = -1;
        int currentSlot = 36 + currentHeld;
        if (currentSlot >= 0 && currentSlot < inventory.length) {
            ItemStack current = inventory[currentSlot];
            if (current != null) currentItemId = current.getId();
        }

        if (!isWatchedItem(currentItemId, watchItems)) {
            // 在快捷栏找
            for (int h = 0; h < 9; h++) {
                int slot = 36 + h;
                if (slot >= inventory.length) continue;
                ItemStack item = inventory[slot];
                if (item == null) continue;
                if (isWatchedItem(item.getId(), watchItems)) {
                    if (h != currentHeld) {
                        inv.switchToSlot(h);
                        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
                        log.info("[InventoryFeature] 切换快捷栏[{}]: {}",
                                h, entry != null ? entry.getName() : "item");
                    }
                    return;
                }
            }

            // 快捷栏没有，在主背包找
            for (int s = 9; s < 36; s++) {
                if (s >= inventory.length) continue;
                ItemStack item = inventory[s];
                if (item == null) continue;
                if (isWatchedItem(item.getId(), watchItems)) {
                    int emptyHotbar = findEmptyHotbarSlot(inventory);
                    if (emptyHotbar < 0) emptyHotbar = 8;
                    if (moveToHotbar(s, emptyHotbar)) {
                        inv.switchToSlot(emptyHotbar);
                        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
                        log.info("[InventoryFeature] 从背包切换快捷栏[{}]: {}",
                                emptyHotbar, entry != null ? entry.getName() : "item");
                    }
                    return;
                }
            }
        }
    }

    // ==================== 6. 更好装备检测提示 ====================

    /**
     * 检测背包/快捷栏中是否有比当前装备更好的护甲，并提示玩家手动更换。
     * 由于协议限制（需要打开背包GUI），无法自动换装，仅提示。
     *
     * 评分逻辑（简化版，基于材质+附魔）：
     * - 材质分：NETHERITE=7, DIAMOND=6, IRON=5, CHAINMAIL=4, GOLDEN=3, LEATHER=2
     * - 部位分：CHESTPLATE=4, LEGGINGS=3, HELMET=2, BOOTS=1
     * - 附魔分：Protection III=3, Unbreaking III=1, Mending=2, 其他I=0.5
     * - 总分 = 材质分*10 + 部位分 + 附魔总分
     */
    private void checkBetterArmor(ConfigManager cfg) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return;

        // 装备槽：5=helmet, 6=chest, 7=leggings, 8=boots
        String[] armorSlotNamesEn = {"Helmet", "Chestplate", "Leggings", "Boots"};
        int[] armorSlots = {5, 6, 7, 8};

        for (int i = 0; i < armorSlots.length; i++) {
            int slot = armorSlots[i];
            ItemStack equipped = inventory[slot];
            int equippedScore = scoreArmor(equipped);
            String slotType = armorSlotNamesEn[i];

            // 在整个背包（快捷栏+主背包）中搜索更好的装备
            int bestSlot = -1;
            int bestScore = equippedScore;

            for (int s = 0; s < inventory.length; s++) {
                // 跳过装备槽本身和副手
                if (s >= 5 && s <= 8) continue;
                if (s == 45) continue;

                ItemStack candidate = inventory[s];
                if (candidate == null) continue;

                // 检查是否是同一部位的护甲
                String armorType = getArmorType(candidate);
                if (!slotType.equals(armorType)) continue;

                // 检查是否是绑定诅咒（不能自动穿的）
                if (hasBindingCurse(candidate)) continue;

                int score = scoreArmor(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = s;
                }
            }

            // 如果找到更好的装备，输出提示
            if (bestSlot >= 0 && !alertedBetterArmor.contains(slot)) {
                ItemStack best = inventory[bestSlot];
                ItemRegistry.ItemEntry bestEntry = ItemRegistry.Instance.getItem(best.getId());
                String bestName = bestEntry != null ? bestEntry.getName() : "unknown";

                ItemRegistry.ItemEntry eqEntry = equipped != null ? ItemRegistry.Instance.getItem(equipped.getId()) : null;
                String eqName = eqEntry != null ? eqEntry.getName() : "空";

                log.info("[InventoryFeature] 【装备提示】{} 位置有更好的装备: {} > {} (请打开背包手动更换)",
                        armorSlotNamesEn[i], bestName, eqName);
                alertedBetterArmor.add(slot);
            }

            // 如果当前装备变好/变空/被替换了，清除警报标记（下次再检测）
            int currentScore = scoreArmor(inventory[slot]);
            if (currentScore > equippedScore) {
                alertedBetterArmor.remove(slot);
            }
        }
    }

    /**
     * 判断物品是否是护甲，以及属于哪个部位
     * @return "Helmet", "Chestplate", "Leggings", "Boots", 或 null（不是护甲）
     */
    private String getArmorType(ItemStack item) {
        if (item == null) return null;
        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
        if (entry == null) return null;
        String name = entry.getName();
        if (name == null) return null;
        name = name.toUpperCase();
        if (name.contains("HELMET") || name.contains("HEAD")) return "Helmet";
        if (name.contains("CHESTPLATE") || name.contains("BREASTPLATE")) return "Chestplate";
        if (name.contains("LEGGINGS") || name.contains("LEG_GUARDS")) return "Leggings";
        if (name.contains("BOOTS") || name.contains("FEET")) return "Boots";
        return null;
    }

    /**
     * 检查物品是否有绑定诅咒附魔
     */
    private boolean hasBindingCurse(ItemStack item) {
        try {
            var dataComponents = item.getDataComponentsPatch();
            if (dataComponents != null) {
                var field = dataComponents.getClass().getDeclaredField("values");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Object, Object> values = (Map<Object, Object>) field.get(dataComponents);
                for (Map.Entry<Object, Object> e : values.entrySet()) {
                    if (e.getKey().toString().contains("Enchantments") ||
                        e.getKey().toString().contains("StoredEnchantments")) {
                        String enchantmentStr = e.getValue().toString().toLowerCase();
                        if (enchantmentStr.contains("binding_curse")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 对护甲物品进行评分（材质 + 附魔）
     */
    private int scoreArmor(ItemStack item) {
        if (item == null) return 0;
        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
        if (entry == null) return 0;
        String name = entry.getName();
        if (name == null) return 0;
        name = name.toUpperCase();

        // 1. 材质分（最重要）
        int materialScore = 0;
        if (name.contains("NETHERITE")) materialScore = 7;
        else if (name.contains("DIAMOND")) materialScore = 6;
        else if (name.contains("IRON")) materialScore = 5;
        else if (name.contains("CHAINMAIL")) materialScore = 4;
        else if (name.contains("GOLDEN") || name.contains("GOLD")) materialScore = 3;
        else if (name.contains("LEATHER")) materialScore = 2;
        else return 0; // 非护甲物品不给分

        // 2. 部位分
        int slotBonus = 0;
        if (name.contains("CHESTPLATE") || name.contains("BREASTPLATE")) slotBonus = 4;
        else if (name.contains("LEGGINGS") || name.contains("LEG_GUARDS")) slotBonus = 3;
        else if (name.contains("HELMET") || name.contains("HEAD")) slotBonus = 2;
        else if (name.contains("BOOTS") || name.contains("FEET")) slotBonus = 1;

        // 3. 附魔分
        int enchantScore = getArmorEnchantScore(item);

        // 总分 = 材质分*10 + 部位分 + 附魔分
        // 这样材质分的权重最大（×10），确保钻石装备永远优先于铁装备
        return materialScore * 10 + slotBonus + enchantScore;
    }

    /**
     * 获取护甲附魔评分
     */
    private int getArmorEnchantScore(ItemStack item) {
        int score = 0;
        try {
            var dataComponents = item.getDataComponentsPatch();
            if (dataComponents != null) {
                var field = dataComponents.getClass().getDeclaredField("values");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Object, Object> values = (Map<Object, Object>) field.get(dataComponents);
                for (Map.Entry<Object, Object> e : values.entrySet()) {
                    String key = e.getKey().toString();
                    // 匹配附魔 key（可能有命名空间前缀，如 minecraft:enchantments）
                    if (!key.contains("Enchantments") && !key.contains("StoredEnchantments")) continue;

                    Object enchantObj = e.getValue();
                    if (enchantObj == null) continue;

                    // 尝试把附魔对象转为字符串并解析
                    String enchantmentStr = enchantObj.toString().toLowerCase();

                    // 用正则匹配所有 "enchant_name:level" 模式
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "([a-z_]+):(\\d+)");
                    java.util.regex.Matcher m = p.matcher(enchantmentStr);
                    while (m.find()) {
                        String enchName = m.group(1);
                        int enchLevel = Integer.parseInt(m.group(2));
                        switch (enchName) {
                            case "protection", "blast_protection", "fire_protection", "projectile_protection" -> score += enchLevel;
                            case "unbreaking" -> score += enchLevel;
                            case "mending" -> score += 2;
                            case "thorns" -> score += 1;
                            case "respiration" -> score += Math.min(enchLevel, 3);
                            case "aqua_affinity" -> score += 1;
                            case "feather_falling" -> score += enchLevel;
                            case "depth_strider" -> score += enchLevel;
                            case "soul_speed" -> score += enchLevel;
                            case "protection_curse" -> score -= enchLevel * 10; // 保护诅咒
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return score;
    }

    /**
     * 从附魔字符串中提取附魔等级（如 "protection:3" → 3）
     */
    private int extractEnchantLevel(String enchantmentStr, String enchantName) {
        // 匹配 "enchantName:数字" 的模式
        try {
            String pattern = enchantName + ":";
            int idx = enchantmentStr.indexOf(pattern);
            if (idx >= 0) {
                String rest = enchantmentStr.substring(idx + pattern.length());
                // 提取连续的数字
                StringBuilder num = new StringBuilder();
                for (char c : rest.toCharArray()) {
                    if (Character.isDigit(c)) num.append(c);
                    else break;
                }
                if (num.length() > 0) return Integer.parseInt(num.toString());
            }
        } catch (Exception ignored) {}
        return 1; // 没找到等级数字，默认1级
    }

    // ==================== 7. 图腾管理（每10秒轮询）====================

    /**
     * 每10秒检查一次副手图腾状态
     * 正确逻辑：
     * 1. 每10秒检查一次
     * 2. 如果副手有图腾则返回空
     * 3. 如果副手没有图腾或者不是图腾则检查第8槽位
     * 4. 如果第8槽位没有图腾或者不是图腾则检查快捷栏其他槽位
     * 5. 如果快捷栏没有图腾则检查背包
     * 6. 如果背包没有图腾则结束并输出警告：图腾数量为0
     * 7. 如果图腾在背包且第8槽位非空，则把图腾放到第8槽位然后放到副手
     * 8. 否则丢掉第八槽位然后继续补充图腾
     */
    /**
     * 检查并补充副手图腾
     * @param inventory 玩家库存数组
     */
    public void checkAndReplenishTotem(ItemStack[] inventory) {
        // 尝试获取图腾动作锁（优先级15高于吃金苹果10）
        if (!SurvivalPlugin.INSTANCE.getActionManager().tryAcquire(ActionManager.ActionType.TOTEM)) {
            log.debug("[InventoryFeature] 图腾检查: 无法获取动作锁（被其他操作占用）");
            return;
        }
        try {
            if (inventory == null || inventory.length <= 45) {
                log.debug("[InventoryFeature] 图腾检查: inventory为空或长度不足");
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastTotemActionTime < TOTEM_ACTION_COOLDOWN) {
                log.debug("[InventoryFeature] 图腾检查: cooldown未到({}ms)", now - lastTotemActionTime);
                return;
            }
            log.info("[InventoryFeature] 开始图腾检查...");
            lastTotemActionTime = now;

            int dedicatedHotbarIdx = SurvivalPlugin.INSTANCE.getConfigManager().getTotemDedicatedSlot();
            int dedicatedSlot = 36 + dedicatedHotbarIdx;

            // 步骤2：检查副手 - 如果有图腾则返回
            if (isTotem(inventory[45])) {
                log.debug("[InventoryFeature] 副手有图腾，跳过检查");
                return;
            }

        // 步骤3：检查第8槽位（专用槽）
        if (dedicatedSlot < inventory.length) {
            ItemStack dedicatedItem = inventory[dedicatedSlot];
            String itemName = "空";
            boolean isTotemItem = false;
            if (dedicatedItem != null) {
                int itemId = dedicatedItem.getId();
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(itemId);
                itemName = (entry != null && entry.getName() != null) ? entry.getName() : "id:" + itemId;
                isTotemItem = isTotem(dedicatedItem);
                // 调试日志：显示实际检测到的名称
                log.debug("[InventoryFeature] 第八槽位物品ID:{} 名称:{} TOTEM_NAME:{}", itemId, itemName, TOTEM_NAME);
            }
            log.info("[InventoryFeature] 副手没有图腾，检查第8槽位：第八槽位：{}，{}图腾", itemName, isTotemItem ? "是" : "不是");
            if (isTotemItem) {
                MovementSync.INSTANCE.getInventoryManager().switchToSlot(dedicatedHotbarIdx);
                sleep(100);
                swapToOffhand();
                log.info("[InventoryFeature] 第八槽位是图腾，换到副手");
                return;
            }
        }

        // 步骤4：检查快捷栏其他槽位
        for (int hotbarIdx = 0; hotbarIdx < 9; hotbarIdx++) {
            if (hotbarIdx == dedicatedHotbarIdx) continue;
            int slot = 36 + hotbarIdx;
            if (slot < inventory.length && isTotem(inventory[slot])) {
                MovementSync.INSTANCE.getInventoryManager().switchToSlot(hotbarIdx);
                sleep(100);
                swapToOffhand();
                log.info("[InventoryFeature] 图腾已补充（快捷栏{}→副手）", hotbarIdx);
                return;
            }
        }

        // 步骤5-8：检查背包并补充
        int totemSlotInInventory = -1;
        for (int slot = 9; slot < 36 && slot < inventory.length; slot++) {
            if (isTotem(inventory[slot])) {
                totemSlotInInventory = slot;
                break;
            }
        }

        // 步骤6：背包没有图腾
        if (totemSlotInInventory < 0) {
            log.warn("[InventoryFeature] 图腾数量为0，无法补充");
            return;
        }

        log.info("[InventoryFeature] 检查背包：图腾位置:{}", totemSlotInInventory);

        // 步骤7：图腾在背包，第8槽位非空则尝试移动到快捷栏空位
        if (dedicatedSlot < inventory.length && inventory[dedicatedSlot] != null) {
            int emptyHotbar = findEmptyHotbarSlot(inventory);
            if (emptyHotbar >= 0 && emptyHotbar != dedicatedHotbarIdx) {
                // 有空位，移动到空位而不是丢弃
                log.info("[InventoryFeature] 快捷栏有空位[{}]，移动槽位{}物品到空位", emptyHotbar, dedicatedHotbarIdx);
                moveToHotbar(dedicatedHotbarIdx, emptyHotbar);
                sleep(150);
            } else {
                // 无空位，丢弃
                log.info("[InventoryFeature] 快捷栏无空位，丢弃槽位{}", dedicatedHotbarIdx);
                discardHotbarSlot(dedicatedHotbarIdx);
                sleep(150);
            }
        }

        // 移动图腾到第8槽位
        if (!moveToHotbar(totemSlotInInventory, dedicatedHotbarIdx)) {
            moveToHotbarTwoPhase(totemSlotInInventory, dedicatedHotbarIdx);
        }
        sleep(200);

        // 重新获取inventory数据并检查第8槽位是否有图腾
        ItemStack[] invData = MovementSync.INSTANCE.getInventoryManager().getInventory();
        if (invData == null || dedicatedSlot >= invData.length || !isTotem(invData[dedicatedSlot])) {
            log.warn("[InventoryFeature] 图腾移动失败，第8槽位无图腾");
            return;
        }

        // 放到副手：先切换到图腾槽位，再交换主副手
        MovementSync.INSTANCE.getInventoryManager().switchToSlot(dedicatedHotbarIdx);
        sleep(150);
        swapToOffhand();
        sleep(100);
        log.info("[InventoryFeature] 图腾已补充（背包{}→第8槽→副手）", totemSlotInInventory);
        } finally {
            // 释放图腾动作锁
            SurvivalPlugin.INSTANCE.getActionManager().release(ActionManager.ActionType.TOTEM);
        }
    }

    /** 判断物品是否为不死图腾 */
    private boolean isTotem(ItemStack item) {
        if (item == null) return false;
        try {
            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null) {
                log.debug("[InventoryFeature] isTotem: entry is null for id={}", item.getId());
                return false;
            }
            if (entry.getName() == null) {
                log.debug("[InventoryFeature] isTotem: entry.getName() is null for id={}", item.getId());
                return false;
            }
            String rawName = entry.getName();
            // 移除空格和下划线，统一规范化
            String normalized = rawName.toLowerCase().replace(" ", "").replace("_", "");
            boolean result = normalized.equals("totemofundying");
            log.debug("[InventoryFeature] isTotem: id={}, rawName='{}', normalized='{}', result={}",
                    item.getId(), rawName, normalized, result);
            return result;
        } catch (Exception e) {
            log.debug("[InventoryFeature] isTotem检查异常: {}", e.getMessage());
            return false;
        }
    }

    /** SWAP_HANDS：副手交换 */
    private void swapToOffhand() {
        var session = Bot.INSTANCE.getSession();
        if (session != null) {
            session.send(new ServerboundPlayerActionPacket(
                PlayerAction.SWAP_HANDS, Vector3i.ZERO, Direction.DOWN, 0));
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            log.debug("[InventoryFeature] 已发送副手交换数据包");
        }
    }

    /** 从指定槽位自动装备到副手并可选使用（右键交互）
     * @param hotbarSlot 快捷栏槽位索引 (0-8)
     * @param useItem 是否右键使用物品
     */
    public void equipToOffhandAndUse(int hotbarSlot, boolean useItem) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        if (inv == null) {
            log.warn("[InventoryFeature] equipToOffhand: InventoryManager为空");
            return;
        }

        // 步骤1：切换到指定槽位
        inv.switchToSlot(hotbarSlot);
        sleep(80);

        // 步骤2：交换到副手
        swapToOffhand();
        sleep(80);

        // 步骤3：可选右键使用物品
        if (useItem) {
            var session = Bot.INSTANCE.getSession();
            if (session != null) {
                // 从副手使用
                session.send(new ServerboundUseItemPacket(
                    Hand.OFF_HAND, 0, 0f, 0f));
                log.info("[InventoryFeature] 已从副手使用物品(槽位{})", hotbarSlot);
            }
        }
    }

    /** 自动穿装备 - 检查指定装备槽位，如有新装备则自动穿戴
     * @param targetEquipmentSlot 目标装备槽位 (8=头盔, 9=胸甲, 10=护腿, 11=靴子)
     * @param newItemId 需要的物品ID
     * @return 是否成功穿戴
     */
    public boolean autoEquip(int targetEquipmentSlot, int newItemId) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] invData = (inv != null) ? inv.getInventory() : null;
        if (invData == null || invData.length <= targetEquipmentSlot) {
            log.debug("[InventoryFeature] autoEquip: inventory为空或槽位无效");
            return false;
        }

        // 步骤1：检查当前装备槽是否有物品
        ItemStack currentEquipment = invData[targetEquipmentSlot];
        if (currentEquipment != null) {
            log.debug("[InventoryFeature] 装备槽{}已有物品，无需更换", targetEquipmentSlot);
            return false;
        }

        // 步骤2：检查背包和快捷栏是否有目标物品
        int foundSlot = -1;
        for (int i = 9; i < invData.length; i++) {
            if (i >= 36 && i < 45) continue; // 跳过快捷栏
            ItemStack item = invData[i];
            if (item != null && item.getId() == newItemId) {
                foundSlot = i;
                break;
            }
        }

        // 如果没找到，返回false
        if (foundSlot == -1) {
            return false;
        }

        // 步骤3：优先用快捷栏空槽，没有空则用配置的可配置槽位
        int emptyHotbar = findEmptyHotbar();
        int targetHotbarSlot = -1;

        if (emptyHotbar >= 0) {
            // 有空槽，用空槽
            targetHotbarSlot = emptyHotbar;
            log.debug("[InventoryFeature] autoEquip: 使用快捷栏空槽{}", emptyHotbar);
        } else {
            // 没有空槽，用配置的专用槽位
            int dedicatedSlot = SurvivalPlugin.INSTANCE.getConfigManager().getEquipmentDedicatedSlot();
            targetHotbarSlot = dedicatedSlot;
            log.debug("[InventoryFeature] autoEquip: 无空槽，使用专用槽位{}", dedicatedSlot);

            // 如果专用槽有物品，先丢弃
            int dedicatedSlotPos = 36 + dedicatedSlot;
            if (dedicatedSlotPos < invData.length && invData[dedicatedSlotPos] != null) {
                inv.switchToSlot(dedicatedSlot);
                sleep(80);
                discardHotbarSlot(dedicatedSlot);
                sleep(80);
            }
        }

        // 步骤4：将物品移动到目标槽位
        if (foundSlot >= 36 && foundSlot < 45) {
            // 物品已在快捷栏
            int sourceHotbarIdx = foundSlot - 36;
            inv.switchToSlot(sourceHotbarIdx);
            sleep(80);
        } else {
            // 物品在背包，需要先放到快捷栏
            log.info("[InventoryFeature] autoEquip: 物品在背包槽位{}，需手动移动", foundSlot);
            return false;
        }

        // 切换到目标槽位
        if (targetHotbarSlot >= 0) {
            inv.switchToSlot(targetHotbarSlot);
            sleep(80);
        }

        // 步骤5：使用物品交互（右键点击）来穿戴装备
        var session = Bot.INSTANCE.getSession();
        if (session != null) {
            // 发送使用物品包（右键）来穿戴装备
            session.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, 0, 0f, 0f));
            log.info("[InventoryFeature] autoEquip: 已尝试穿戴装备到槽位{}", targetEquipmentSlot);
        }

        return true;
    }

    /** 安全切槽位 */
    private void setHeldSlotSafe(int hotbarIndex) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        if (inv != null) {
            inv.setHeldSlot(hotbarIndex);
        }
    }

    /** 安全sleep */
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ==================== 辅助方法 ====================

    /** 找到快捷栏中第一个空位，返回 hotbar index (0-8)，未找到返回 -1 */
    private int findEmptyHotbar() {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return -1;
        for (int i = 0; i < 9; i++) {
            int slot = 36 + i;
            if (slot >= inventory.length) return -1;
            if (inventory[slot] == null) return i;
        }
        return -1;
    }

    /** 找到快捷栏中第一个空位，接受inventory数组参数 */
    private int findEmptyHotbarSlot(ItemStack[] inventory) {
        if (inventory == null) return -1;
        for (int i = 0; i < 9; i++) {
            int slot = 36 + i;
            if (slot >= inventory.length) return -1;
            if (inventory[slot] == null) return i;
        }
        return -1;
    }

    // ==================== 背包查看（双语）====================

    public void printInventoryView() {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) {
            log.info("[InventoryFeature] 无法读取背包数据");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== Inventory / 背包 ==========\n");

        // 快捷栏 / Hotbar
        sb.append("\n[Hotbar / 快捷栏]\n");
        for (int i = 0; i < 9; i++) {
            int slot = 36 + i;
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item != null) {
                int amount = 0;
                try { amount = item.getAmount(); } catch (Exception ignored) {}
                sb.append(String.format("  [%d] %s x%d\n",
                        i, ItemTranslator.toBilingual(item.getId()), amount));
            } else {
                sb.append(String.format("  [%d] (empty / 空)\n", i));
            }
        }

        // 副手 / Offhand
        ItemStack offhand = inventory.length > 45 ? inventory[45] : null;
        if (offhand != null) {
            int amount = 0;
            try { amount = offhand.getAmount(); } catch (Exception ignored) {}
            sb.append(String.format("\n[Offhand / 副手] %s x%d\n",
                    ItemTranslator.toBilingual(offhand.getId()), amount));
        } else {
            sb.append("\n[Offhand / 副手] (empty / 空)\n");
        }

        // 装甲 / Armor
        sb.append("\n[Armor / 装备]\n");
        String[] armorNames = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int i = 0; i < 4; i++) {
            int slot = 5 + i;
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item != null) {
                sb.append(String.format("  [%s] %s\n",
                        armorNames[i], ItemTranslator.toBilingual(item.getId())));
            }
        }

        // 主背包 / Main Inventory
        sb.append("\n[Main Inventory / 主背包]\n");
        int count = 0;
        for (int slot = 9; slot < 36; slot++) {
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item != null) {
                int amount = 0;
                try { amount = item.getAmount(); } catch (Exception ignored) {}
                sb.append(String.format("  %s x%d\n",
                        ItemTranslator.toBilingual(item.getId()), amount));
                count++;
            }
        }
        if (count == 0) sb.append("  (empty / 空)\n");

        sb.append("========================================\n");
        log.info(sb.toString());
    }

    // ==================== 辅助方法 ====================

    private boolean isWatchedItem(int itemId, List<String> watchItems) {
        if (itemId < 0) return false;
        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(itemId);
        if (entry == null) return false;
        String name = entry.getName();
        if (name == null) return false;
        name = name.toUpperCase();
        for (String w : watchItems) {
            String wu = w.toUpperCase();
            if (name.contains(wu) || wu.contains(name)) return true;
        }
        return false;
    }

    private int findItemInHotbarByName(String name) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return -1;
        for (int h = 0; h < 9; h++) {
            int slot = 36 + h;
            if (slot < inventory.length && inventory[slot] != null) {
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(inventory[slot].getId());
                if (entry != null && entry.getName().contains(name)) {
                    return h;
                }
            }
        }
        return -1;
    }

    private int findItemInInventoryByName(String name) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return -1;
        for (int s = 9; s < 36; s++) {
            if (s < inventory.length && inventory[s] != null) {
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(inventory[s].getId());
                if (entry != null && entry.getName().contains(name)) {
                    return s;
                }
            }
        }
        return -1;
    }

    private int findItemIdInHotbar(int itemId) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return -1;
        for (int h = 0; h < 9; h++) {
            int slot = 36 + h;
            if (slot < inventory.length && inventory[slot] != null
                    && inventory[slot].getId() == itemId) {
                return h;
            }
        }
        return -1;
    }

    private int findItemIdInInventory(int itemId) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return -1;
        for (int s = 9; s < 36; s++) {
            if (s < inventory.length && inventory[s] != null
                    && inventory[s].getId() == itemId) {
                return s;
            }
        }
        return -1;
    }

    /**
     * 查找快捷栏中可丢弃的物品槽位
     * 优先级：从不重要的物品开始丢弃
     * 跳过：工具、武器、食物、图腾等重要物品
     */
    private int findDiscardableHotbarSlot(ItemStack[] inventory) {
        if (inventory == null) return -1;
        
        // 丢弃优先级列表（数字越小越先丢弃）
        // 0=方块/建筑材料, 1=无附魔武器, 2=低级燃料, 3=低级工具, 4=低级盔甲
        int bestSlot = -1;
        int bestPriority = Integer.MAX_VALUE;
        
        for (int i = 0; i < 9; i++) {
            int slot = 36 + i;
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item == null) continue;
            
            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null) continue;
            String name = entry.getName();
            if (name == null) continue;
            name = name.toUpperCase();
            
            int priority = getDiscardPriority(name);
            
            // 跳过不可丢弃的物品（附魔工具/武器、食物、图腾等）
            if (priority < 0) continue;
            
            if (priority < bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }
        
        return bestSlot;
    }
    
    /**
     * 获取物品丢弃优先级
     * 返回值越小越先丢弃，-1表示不可丢弃
     */
    private int getDiscardPriority(String itemName) {
        if (itemName == null) return -1;
        
        // 永远不可丢弃的物品
        if (itemName.contains("TOTEM")) return -1;
        if (itemName.contains("GOLDEN_APPLE") || itemName.contains("ENCHANTED_GOLDEN_APPLE")) return -1;
        if (itemName.contains("BREAD") || itemName.contains("COOKED") || itemName.contains("RAW_FOOD")) return -1;
        if (itemName.contains("GAPPLE")) return -1;
        if (itemName.contains("EXPERIENCE") || itemName.contains("BOTTLE")) return -1;
        if (itemName.contains("ENDER_PEARL")) return -1;
        if (itemName.contains("WATER_BUCKET") || itemName.contains("LAVA_BUCKET")) return -1;
        if (itemName.contains("COMPASS")) return -1;
        if (itemName.contains("MAP")) return -1;
        
        // 附魔的工具/武器/盔甲不可丢弃
        if (itemName.contains("DIAMOND") || itemName.contains("NETHERITE")) {
            if (itemName.contains("SWORD") || itemName.contains("PICKAXE") || 
                itemName.contains("AXE") || itemName.contains("SHOVEL") || 
                itemName.contains("HOE") || itemName.contains("HELMET") ||
                itemName.contains("CHESTPLATE") || itemName.contains("LEGGINGS") ||
                itemName.contains("BOOTS") || itemName.contains("SHIELD")) {
                return -1;
            }
        }
        if (itemName.contains("IRON") && !itemName.contains("IRON_INGOT")) {
            if (itemName.contains("SWORD") || itemName.contains("PICKAXE") || 
                itemName.contains("AXE") || itemName.contains("SHOVEL") || 
                itemName.contains("HOE") || itemName.contains("HELMET") ||
                itemName.contains("CHESTPLATE") || itemName.contains("LEGGINGS") ||
                itemName.contains("BOOTS")) {
                return -1;
            }
        }
        
        // 可丢弃的物品，按优先级排序
        // 方块/建筑材料
        if (itemName.contains("COBBLESTONE") || itemName.contains("DIRT") || 
            itemName.contains("GRAVEL") || itemName.contains("SAND") ||
            itemName.contains("COAL_ORE") || itemName.contains("IRON_ORE") ||
            itemName.contains("GOLD_ORE") || itemName.contains("DIAMOND_ORE") ||
            itemName.contains("DEBRIS")) {
            return 1;
        }
        
        // 低级燃料
        if (itemName.contains("COAL") && !itemName.contains("CHARCOAL")) return 2;
        if (itemName.contains("CHARCOAL")) return 2;
        
        // 木制工具/武器
        if (itemName.contains("WOODEN_SWORD") || itemName.contains("WOODEN_PICKAXE") ||
            itemName.contains("WOODEN_AXE") || itemName.contains("WOODEN_SHOVEL") ||
            itemName.contains("WOODEN_HOE")) {
            return 3;
        }
        
        // 石制工具/武器
        if (itemName.contains("STONE_SWORD") || itemName.contains("STONE_PICKAXE") ||
            itemName.contains("STONE_AXE") || itemName.contains("STONE_SHOVEL") ||
            itemName.contains("STONE_HOE")) {
            return 4;
        }
        
        // 低级盔甲
        if (itemName.contains("LEATHER_") || itemName.contains("CHAINMAIL_")) {
            return 5;
        }
        
        // 低级护盾
        if (itemName.contains("BOW") && !itemName.contains("CROSSBOW")) {
            return 6;
        }
        
        // 其他杂物
        return 10;
    }

    /** 把主背包槽移动到快捷栏槽（使用 MOVE_TO_HOTBAR_SLOT，无需打开背包界面） */
    private boolean moveToHotbar(int invSlot, int hotbarSlot) {
        if (invSlot < 9 || invSlot >= 36) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var session = Bot.INSTANCE.getSession();
        if (session == null) return false;
        int stateId = com.example.survival.listeners.PacketListener.getContainerStateId();
        try {
            var action = switch (hotbarSlot) {
                case 0 -> MoveToHotbarAction.SLOT_1;
                case 1 -> MoveToHotbarAction.SLOT_2;
                case 2 -> MoveToHotbarAction.SLOT_3;
                case 3 -> MoveToHotbarAction.SLOT_4;
                case 4 -> MoveToHotbarAction.SLOT_5;
                case 5 -> MoveToHotbarAction.SLOT_6;
                case 6 -> MoveToHotbarAction.SLOT_7;
                case 7 -> MoveToHotbarAction.SLOT_8;
                case 8 -> MoveToHotbarAction.SLOT_9;
                default -> MoveToHotbarAction.SLOT_1;
            };
            session.send(new ServerboundContainerClickPacket(
                    0, stateId, invSlot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.MOVE_TO_HOTBAR_SLOT,
                    action,
                    null,
                    Collections.emptyMap()));
            log.debug("[InventoryFeature] moveToHotbar: invSlot={}, hotbarSlot={}, stateId={}", invSlot, hotbarSlot, stateId);
            return true;
        } catch (Exception e) {
            log.debug("[InventoryFeature] moveToHotbar失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 两阶段点击方式将主背包物品移动到快捷栏（备用方案）
     * 第一阶段：点击主背包槽拾取物品
     * 第二阶段：点击快捷栏槽放置物品
     */
    private boolean moveToHotbarTwoPhase(int invSlot, int hotbarSlot) {
        if (invSlot < 9 || invSlot >= 36) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var session = Bot.INSTANCE.getSession();
        if (session == null) return false;
        int stateId = com.example.survival.listeners.PacketListener.getContainerStateId();
        int hotbarActualSlot = 36 + hotbarSlot;
        
        try {
            // 第一阶段：拾取主背包物品（LEFT_CLICK）
            session.send(new ServerboundContainerClickPacket(
                    0, stateId, invSlot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.CLICK_ITEM,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK,
                    null,
                    Collections.emptyMap()));
            
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            
            // 第二阶段：放置到快捷栏
            session.send(new ServerboundContainerClickPacket(
                    0, stateId, hotbarActualSlot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.CLICK_ITEM,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK,
                    null,
                    Collections.emptyMap()));
            
            log.debug("[InventoryFeature] moveToHotbarTwoPhase: invSlot={}, hotbarSlot={}", invSlot, hotbarSlot);
            return true;
        } catch (Exception e) {
            log.debug("[InventoryFeature] moveToHotbarTwoPhase失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 丢弃快捷栏指定槽位的物品
     * @param hotbarSlot 快捷栏索引 (0-8)
     */
    private void discardHotbarSlot(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) return;
        var session = Bot.INSTANCE.getSession();
        if (session == null) {
            log.warn("[InventoryFeature] discardHotbarSlot: session is null");
            return;
        }
        
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        int originalSlot = inv.getHeldSlot();
        
        try {
            log.info("[InventoryFeature] 开始丢弃快捷栏[{}]，原槽位:{}", hotbarSlot, originalSlot);
            
            // 切换到目标槽位
            inv.switchToSlot(hotbarSlot);
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            
            // 丢弃物品
            session.send(new ServerboundPlayerActionPacket(
                    PlayerAction.DROP_ITEM, Vector3i.ZERO, Direction.DOWN, 0));
            
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            
            // 恢复到原槽位
            inv.switchToSlot(originalSlot);
            
            log.info("[InventoryFeature] 已丢弃快捷栏[{}]腾出位置", hotbarSlot);
        } catch (Exception e) {
            log.debug("[InventoryFeature] 丢弃快捷栏物品失败: {}", e.getMessage());
            // 尝试恢复到原槽位
            try { inv.switchToSlot(originalSlot); } catch (Exception ignored) {}
        }
    }

    /** 使用 MOVE_TO_HOTBAR_SLOT 从主背包移动到快捷栏（无需打开背包界面） */
    private boolean shiftMoveToHotbar(int invSlot, int hotbarSlot) {
        if (invSlot < 9 || invSlot >= 36) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var session = Bot.INSTANCE.getSession();
        if (session == null) return false;
        int stateId = com.example.survival.listeners.PacketListener.getContainerStateId();
        try {
            var action = switch (hotbarSlot) {
                case 0 -> MoveToHotbarAction.SLOT_1;
                case 1 -> MoveToHotbarAction.SLOT_2;
                case 2 -> MoveToHotbarAction.SLOT_3;
                case 3 -> MoveToHotbarAction.SLOT_4;
                case 4 -> MoveToHotbarAction.SLOT_5;
                case 5 -> MoveToHotbarAction.SLOT_6;
                case 6 -> MoveToHotbarAction.SLOT_7;
                case 7 -> MoveToHotbarAction.SLOT_8;
                case 8 -> MoveToHotbarAction.SLOT_9;
                default -> MoveToHotbarAction.SLOT_1;
            };
            session.send(new ServerboundContainerClickPacket(
                    0, stateId, invSlot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.MOVE_TO_HOTBAR_SLOT,
                    action,
                    null,
                    Collections.emptyMap()));
            log.debug("[InventoryFeature] shiftMoveToHotbar: invSlot={}, hotbarSlot={}, stateId={}", invSlot, hotbarSlot, stateId);
            return true;
        } catch (Exception e) {
            log.debug("[InventoryFeature] shiftMoveToHotbar失败: {}", e.getMessage());
            return false;
        }
    }

    private int findItemIdByName(String name) {
        try {
            for (int id = 1; id <= 759; id++) {
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(id);
                if (entry != null) {
                    String itemName = entry.getName();
                    if (itemName != null && itemName.toUpperCase().contains(name)) {
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private int getMaxDurability(String itemName) {
        if (itemName == null) return -1;
        itemName = itemName.toUpperCase();

        if (itemName.contains("NETHERITE")) {
            if (itemName.contains("PICKAXE") || itemName.contains("AXE") ||
                itemName.contains("SHOVEL") || itemName.contains("HOE")) return 2031;
            if (itemName.contains("SWORD")) return 2031;
        }
        if (itemName.contains("DIAMOND")) {
            if (itemName.contains("PICKAXE") || itemName.contains("AXE") ||
                itemName.contains("SHOVEL") || itemName.contains("HOE")) return 1561;
            if (itemName.contains("SWORD")) return 1561;
        }
        if (itemName.contains("IRON")) {
            if (itemName.contains("PICKAXE") || itemName.contains("AXE") ||
                itemName.contains("SHOVEL") || itemName.contains("HOE")) return 250;
            if (itemName.contains("SWORD")) return 250;
        }
        if (itemName.contains("STONE")) {
            if (itemName.contains("PICKAXE") || itemName.contains("AXE") ||
                itemName.contains("SHOVEL") || itemName.contains("HOE")) return 131;
            if (itemName.contains("SWORD")) return 131;
        }
        if (itemName.contains("WOODEN") || itemName.contains("GOLDEN")) {
            if (itemName.contains("PICKAXE") || itemName.contains("AXE") ||
                itemName.contains("SHOVEL") || itemName.contains("HOE")) return 32;
            if (itemName.contains("SWORD")) return 32;
        }

        if (itemName.contains("NETHERITE_HELMET")) return 407;
        if (itemName.contains("NETHERITE_CHESTPLATE")) return 592;
        if (itemName.contains("NETHERITE_LEGGINGS")) return 555;
        if (itemName.contains("NETHERITE_BOOTS")) return 481;

        if (itemName.contains("DIAMOND_HELMET")) return 363;
        if (itemName.contains("DIAMOND_CHESTPLATE")) return 528;
        if (itemName.contains("DIAMOND_LEGGINGS")) return 495;
        if (itemName.contains("DIAMOND_BOOTS")) return 429;

        if (itemName.contains("IRON_HELMET")) return 165;
        if (itemName.contains("IRON_CHESTPLATE")) return 240;
        if (itemName.contains("IRON_LEGGINGS")) return 225;
        if (itemName.contains("IRON_BOOTS")) return 195;

        if (itemName.contains("CHAINMAIL_HELMET")) return 165;
        if (itemName.contains("CHAINMAIL_CHESTPLATE")) return 240;
        if (itemName.contains("CHAINMAIL_LEGGINGS")) return 225;
        if (itemName.contains("CHAINMAIL_BOOTS")) return 195;

        if (itemName.contains("GOLDEN_HELMET")) return 77;
        if (itemName.contains("GOLDEN_CHESTPLATE")) return 112;
        if (itemName.contains("GOLDEN_LEGGINGS")) return 105;
        if (itemName.contains("GOLDEN_BOOTS")) return 91;

        if (itemName.contains("LEATHER_HELMET")) return 55;
        if (itemName.contains("LEATHER_CHESTPLATE")) return 80;
        if (itemName.contains("LEATHER_LEGGINGS")) return 75;
        if (itemName.contains("LEATHER_BOOTS")) return 65;

        if (itemName.contains("SHIELD")) return 336;
        if (itemName.contains("BOW")) return 384;
        if (itemName.contains("CROSSBOW")) return 465;
        if (itemName.contains("TRIDENT")) return 250;
        if (itemName.contains("ELYTRA")) return 432;
        if (itemName.contains("FISHING_ROD")) return 64;
        if (itemName.contains("FLINT_AND_STEEL")) return 64;
        if (itemName.contains("SHEARS")) return 238;

        return -1;
    }
}
