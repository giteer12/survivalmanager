package com.example.survival.features;

import com.example.survival.SurvivalPlugin;
import com.example.survival.listeners.PacketListener;
import xin.bbtt.MovementSync;
import xin.bbtt.inventory.InventoryManager;
import xin.bbtt.inventory.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 自动进食
 * 
 * 动作协调：
 *  - 进食前通过 ActionManager.tryAcquire(EAT) 获取执行权限
 *    （高优先级，会打断正在进行的战斗/移动）
 *  - 进食完成后释放锁
 *  - 如果是金苹果，只要生命值低于18就优先执行（不受其他动作影响）
 */
public class AutoEat {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    private volatile boolean enabled = false;
    private ScheduledExecutorService scheduler;

    private volatile long lastEatTime = 0;
    private static final long EAT_COOLDOWN = 2000;
    private volatile boolean isEating = false;
    private volatile long eatStartTime = 0;
    private static final long EAT_DURATION = 1800;

    private static final float EAT_HEALTH_THRESHOLD = 18.0f;

    public void enable() {
        if (enabled) return;
        enabled = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoEat-Tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 500, 250, TimeUnit.MILLISECONDS);
        log.info("[AutoEat] 已启用");
    }

    public void disable() {
        enabled = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        isEating = false;
        SurvivalPlugin.INSTANCE.getActionManager().forceRelease();
        log.info("[AutoEat] 已禁用");
    }

    public boolean isEnabled() { return enabled; }

    private void tick() {
        if (!enabled) return;

        long now = System.currentTimeMillis();

        if (isEating) {
            if (now - eatStartTime >= EAT_DURATION) {
                isEating = false;
                SurvivalPlugin.INSTANCE.getActionManager().release(ActionManager.ActionType.EAT);
                lastEatTime = now;
                log.debug("[AutoEat] 进食完成");
            }
            return;
        }

        if (now - lastEatTime < EAT_COOLDOWN) return;

        float healthThreshold = (float) SurvivalPlugin.INSTANCE.getConfigManager().getAutoEatHealthThreshold();
        float saturationThreshold = (float) SurvivalPlugin.INSTANCE.getConfigManager().getAutoEatSaturationThreshold();
        boolean respectAbsorption = SurvivalPlugin.INSTANCE.getConfigManager().isAutoEatRespectAbsorption();

        float currentHealth = SurvivalPlugin.INSTANCE.getPacketListener().getHealth();
        float currentSaturation = SurvivalPlugin.INSTANCE.getPacketListener().getSaturationLevel();
        float currentAbsorption = SurvivalPlugin.INSTANCE.getPacketListener().getAbsorptionAmount();

        boolean needEat = currentHealth < healthThreshold || currentSaturation < saturationThreshold;
        if (!needEat) return;
        if (currentHealth >= 20.0f && currentSaturation >= 20.0f) return;

        // 健康度危急（<18）时优先吃金苹果
        boolean urgent = currentHealth < EAT_HEALTH_THRESHOLD;

        FoodChoice bestFood = findBestFood(respectAbsorption, currentAbsorption, urgent, currentHealth);
        if (bestFood == null) return;

        // 通过动作管理器获取执行权限（吃东西优先级高）
        if (!SurvivalPlugin.INSTANCE.getActionManager().tryAcquire(ActionManager.ActionType.EAT)) {
            log.debug("[AutoEat] 动作管理器被占用({})，跳过这次进食", 
                SurvivalPlugin.INSTANCE.getActionManager().getStatus());
            return;
        }

        eatFood(bestFood);
    }

    private FoodChoice findBestFood(boolean respectAbsorption, float currentAbsorption, boolean urgent, float h) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        ItemStack[] inventory = inv.getInventory();
        if (inventory == null) return null;

        List<String> priorityList = SurvivalPlugin.INSTANCE.getConfigManager().getAutoEatPriority();
        List<String> blacklist = SurvivalPlugin.INSTANCE.getConfigManager().getAutoEatBlacklist();

        // 危急时：优先找金苹果
        if (urgent) {
            FoodChoice goldenApple = findGoldenApple(inventory, blacklist);
            if (goldenApple != null) {
                log.info("[AutoEat] 危急({}/20), 优先吃金苹果", (int)h);
                return goldenApple;
            }
        }

        // 按优先级列表查找
        for (String foodName : priorityList) {
            String searchName = foodName.toUpperCase();

            if (respectAbsorption && currentAbsorption > 0.1f) {
                if (searchName.contains("GOLDEN_APPLE")) continue;
            }

            for (int slot = 36; slot < 45; slot++) {
                if (slot >= inventory.length) continue;
                ItemStack item = inventory[slot];
                if (item == null) continue;

                String itemName = ItemRegistry.Instance.getItem(item.getId()).getName();
                if (itemName == null) continue;
                itemName = itemName.toUpperCase();

                boolean blacklisted = false;
                for (String black : blacklist) {
                    if (itemName.contains(black.toUpperCase())) {
                        blacklisted = true;
                        break;
                    }
                }
                if (blacklisted) continue;

                if (itemName.contains(searchName) || searchName.contains(itemName)) {
                    int hotbarSlot = slot - 36;
                    return new FoodChoice(hotbarSlot, itemName, foodName);
                }
            }
        }

        // 回退：搜索任何可食用物品
        for (int slot = 36; slot < 45; slot++) {
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item == null) continue;

            String itemName = ItemRegistry.Instance.getItem(item.getId()).getName();
            if (itemName == null) continue;
            itemName = itemName.toUpperCase();

            boolean blacklisted = false;
            for (String black : blacklist) {
                if (itemName.contains(black.toUpperCase())) {
                    blacklisted = true;
                    break;
                }
            }
            if (blacklisted) continue;

            float[] foodValue = PacketListener.getFoodValue(itemName);
            if (foodValue != null && foodValue[0] > 0) {
                if (respectAbsorption && currentAbsorption > 0.1f) {
                    if (itemName.contains("GOLDEN_APPLE")) continue;
                }
                int hotbarSlot = slot - 36;
                return new FoodChoice(hotbarSlot, itemName, itemName);
            }
        }

        return null;
    }

    /** 在快捷栏中查找金苹果 */
    private FoodChoice findGoldenApple(ItemStack[] inventory, List<String> blacklist) {
        for (int slot = 36; slot < 45; slot++) {
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item == null) continue;

            String itemName = ItemRegistry.Instance.getItem(item.getId()).getName();
            if (itemName == null) continue;
            itemName = itemName.toUpperCase();

            if (!itemName.contains("GOLDEN_APPLE")) continue;

            boolean blacklisted = false;
            for (String black : blacklist) {
                if (itemName.contains(black.toUpperCase())) {
                    blacklisted = true;
                    break;
                }
            }
            if (blacklisted) continue;

            int hotbarSlot = slot - 36;
            return new FoodChoice(hotbarSlot, itemName, "金苹果");
        }

        // 主背包搜索金苹果
        for (int slot = 9; slot < 36; slot++) {
            if (slot >= inventory.length) continue;
            ItemStack item = inventory[slot];
            if (item == null) continue;

            String itemName = ItemRegistry.Instance.getItem(item.getId()).getName();
            if (itemName == null) continue;
            itemName = itemName.toUpperCase();

            if (!itemName.contains("GOLDEN_APPLE")) continue;

            boolean blacklisted = false;
            for (String black : blacklist) {
                if (itemName.contains(black.toUpperCase())) {
                    blacklisted = true;
                    break;
                }
            }
            if (blacklisted) continue;

            // 尝试移到快捷栏
            int emptyHotbar = findEmptyHotbarSlot(inventory);
            if (emptyHotbar < 0) emptyHotbar = 8;
            if (moveToHotbar(slot, emptyHotbar)) {
                return new FoodChoice(emptyHotbar, itemName, "金苹果(从背包)");
            }
        }

        return null;
    }

    private void eatFood(FoodChoice food) {
        try {
            InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
            inv.switchToSlot(food.hotbarSlot);

            // 直接发送使用物品包
            var session = xin.bbtt.mcbot.Bot.INSTANCE.getSession();
            if (session != null) {
                session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket(
                        Hand.MAIN_HAND, 0, 0f, 0f
                ));
            }

            log.info("[AutoEat] 正在食用 {} (槽位: {})", food.displayName, food.hotbarSlot);
            isEating = true;
            eatStartTime = System.currentTimeMillis();

        } catch (Exception e) {
            log.error("[AutoEat] 进食异常", e);
            isEating = false;
            SurvivalPlugin.INSTANCE.getActionManager().release(ActionManager.ActionType.EAT);
        }
    }

    private int findEmptyHotbarSlot(ItemStack[] inventory) {
        if (inventory == null) return -1;
        for (int h = 0; h < 9; h++) {
            int slot = 36 + h;
            if (slot < inventory.length && inventory[slot] == null) return h;
        }
        return -1;
    }

    /** 把主背包物品移到快捷栏 */
    private boolean moveToHotbar(int invSlot, int hotbarSlot) {
        if (invSlot < 9 || invSlot >= 36) return false;
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        var session = xin.bbtt.mcbot.Bot.INSTANCE.getSession();
        if (session == null) return false;

        int stateId = PacketListener.getContainerStateId();
        try {
            // CLICK_ITEM + LEFT_CLICK 拾取 → 放到快捷栏
            session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket(
                    0, stateId, invSlot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.CLICK_ITEM,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK,
                    null,
                    Collections.emptyMap()));
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket(
                    0, stateId, 36 + hotbarSlot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.CLICK_ITEM,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK,
                    null,
                    Collections.emptyMap()));
            return true;
        } catch (Exception e) {
            log.debug("[AutoEat] moveToHotbar失败: {}", e.getMessage());
            return false;
        }
    }

    private record FoodChoice(int hotbarSlot, String itemName, String displayName) {}
}
