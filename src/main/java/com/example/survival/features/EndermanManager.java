package com.example.survival.features;

import com.example.survival.SurvivalPlugin;
import xin.bbtt.MovementSync;
import xin.bbtt.Entity.Entity;
import xin.bbtt.inventory.InventoryManager;
import xin.bbtt.inventory.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import com.example.survival.utils.ConfigManager;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 反击管理器 - 被任何实体攻击时自动反击
 * 
 * 特点：
 *  - 被玩家攻击也会反击（好友列表中的玩家除外）
 *  - 反击时不转视线（只用 InteractEntityMovement 攻击）
 *  - 反击与 KillAura 相互独立
 *  - 目标消失/不在视野内/连续10次攻击失败则取消追击
 */
public class EndermanManager {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    private volatile boolean enabled = false;
    private ScheduledExecutorService scheduler;

    // 追击列表
    private final Map<Integer, Long> chasingEntities = new ConcurrentHashMap<>();

    // 攻击冷却
    private final Map<Integer, Long> lastAttackTime = new ConcurrentHashMap<>();
    private static final long ATTACK_COOLDOWN = 1000;

    // 攻击失败计数器（仅在 onEntityDamage startChase 时清理）
    private final Map<Integer, Integer> attackFailCount = new ConcurrentHashMap<>();

    public void enable() {
        if (enabled) return;
        enabled = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EndermanManager-Tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 200, 100, TimeUnit.MILLISECONDS);
        log.info("[EndermanManager] 已启用");
    }

    public void disable() {
        enabled = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        chasingEntities.clear();
        lastAttackTime.clear();
        attackFailCount.clear();
        log.info("[EndermanManager] 已禁用");
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 当实体受伤时调用（来自 PacketListener.onDamageEvent）
     * 
     * 规则：
     *  - 攻击者是好友列表中的玩家 → 忽略
     *  - 攻击者是末影人/怪物/非好友玩家 → 加入追击列表
     *  - 攻击来源未知 → 就近搜索怪物
     */
    /** 非生物实体 + 自伤来源（不反击） */
    private static final Set<String> NON_HOSTILE_TYPES = Set.of(
        // 投射物
        "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING", "ITEM",
        "ARROW", "SPECTRAL_ARROW", "FIREBALL", "SMALL_FIREBALL",
        "SHULKER_BULLET", "LLAMA_SPIT", "ENDER_PEARL",
        "LEASH_KNOT", "FISHING_BOBBER", "AREA_EFFECT_CLOUD",
        "DRAGON_FIREBALL", "WITHER_SKULL", "FIREWORK_ROCKET",
        "EXPERIENCE_ORB", "EXPERIENCE_BOTTLE", "POTION",
        "TRIDENT", "SNOWBALL", "EGG",
        "FALLING_BLOCK", "MINECART", "BOAT", "CHEST_BOAT",
        "ARMOR_STAND", "EVOKER_FANGS", "MARKER",
        // 自伤来源（attackerId < 0 的常见原因）
        "ANVIL", "HOT_FLOOR", "MAGMA_BLOCK",
        "LAVA", "FIRE", "CACTUS", "SWEET_BERRY_BUSH",
        "WITHER", "ELDER_GUARDIAN", "GUARDIAN"
    );

    public void onEntityDamage(int attackerId) {
        if (!enabled) return;

        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        if (!cfg.isEndermanKillIfAttacked()) return;

        // ---------- 自伤（铁砧/摔落/岩浆块等）：静默忽略 ----------
        if (attackerId < 0) {
            return;
        }

        // ---------- 自己攻击自己：查找最近的敌对实体 ----------
        if (attackerId == MovementSync.INSTANCE.entityId) {
            Entity nearest = findNearestHostile(5);
            if (nearest == null) return;
            attackerId = nearest.getEntityId();
        }

        Entity attacker = MovementSync.INSTANCE.getWorld().getEntity(attackerId);

        // 投射物已消失 → 反向追溯射手
        if (attacker == null) {
            int shooterId = tryGetShooterFromProjectile(attackerId);
            if (shooterId > 0) {
                attacker = MovementSync.INSTANCE.getWorld().getEntity(shooterId);
            }
        }

        // 仍找不到实体 → 就近搜索5格内敌对实体
        if (attacker == null) {
            Entity nearest = findNearestHostile(5);
            if (nearest == null) return;
            attackerId = nearest.getEntityId();
            attacker = nearest;
        }

        String typeName = attacker.getType().name().toUpperCase();

        // 非生物实体 → 静默忽略
        if (NON_HOSTILE_TYPES.contains(typeName)) {
            return;
        }

        // ---------- 先解析武器（所有日志在后面统一输出） ----------
        String weapon = getWeaponTypeName();

        // ---------- 玩家攻击 ----------
        if (typeName.equals("PLAYER")) {
            String playerName = getPlayerName(attacker);
            List<String> friends = cfg.getFriends();
            // 好友 → 不追击也不输出日志
            if (playerName != null && friends.contains(playerName)) {
                return;
            }
            // 非好友玩家 → 追击，完成所有动作后再输出日志
            startChase(attackerId);
            log.info("[EndermanManager] 受到玩家 [{}] 攻击，进行反击，武器：{}",
                playerName != null ? playerName : "?", weapon);
            return;
        }

        // ---------- 怪物（末影人/其他） ----------
        startChase(attackerId);
        String monsterType = attacker.getType().name().toLowerCase();
        log.info("[EndermanManager] 受到 {} 攻击，进行反击，武器：{}",
            monsterType, weapon);
    }

    private void startChase(int entityId) {
        chasingEntities.put(entityId, System.currentTimeMillis());
        // 重置失败计数（重新追击）
        attackFailCount.remove(entityId);
    }

    /**
     * 获取当前主手物品的武器类型中文名。
     * 剑 > 斧 > 镐 > 锄 > 其他 > 手（空手）
     */
    private String getWeaponTypeName() {
        try {
            InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
            var inventory = inv.getInventory();
            if (inventory == null) return "手";
            int heldSlot = inv.getHeldSlot();
            int invSlot = 36 + heldSlot;
            if (invSlot >= inventory.length) return "手";
            var item = inventory[invSlot];
            if (item == null) return "手";
            var entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null || entry.getName() == null) return "手";
            String name = entry.getName().toUpperCase();
            if (name.contains("SWORD")) return "剑";
            if (name.contains("AXE")) return "斧";
            if (name.contains("PICKAXE")) return "镐";
            if (name.contains("SHOVEL")) return "锹";
            if (name.contains("HOE")) return "锄";
            if (name.contains("TRIDENT")) return "三叉戟";
            if (name.contains("BOW")) return "弓";
            if (name.contains("CROSSBOW")) return "弩";
            return "手"; // 空手或非武器物品
        } catch (Exception e) {
            return "手";
        }
    }

    /**
     * 尝试从投射物实体追溯射击者 ID。
     * 投射物的 Shooter metadata 键在 MC 1.21+ 中为 "owner" 或数字索引。
     * 如果追溯失败返回 -1。
     */
    private int tryGetShooterFromProjectile(int projectileId) {
        Entity proj = MovementSync.INSTANCE.getWorld().getEntity(projectileId);
        if (proj == null) return -1;
        
        // 尝试从 metadata 读射击者（IntMetadataData，键 "M" 或数字 8）
        var meta = proj.getMetadata();
        
        // 方式1：通过字符串键 "owner"
        Object val = meta.get("owner");
        if (val instanceof Integer shooterId) {
            return shooterId;
        }
        // 方式2：通过数字索引（MC metadata 投射物 shooter = index 8）
        try {
            var entry = meta.get("8");
            if (entry instanceof Integer s) return s;
        } catch (Exception ignored) {}
        
        return -1;
    }

    /**
     * 获取实体的自定义名称（CustomName 元数据）
     */
    private String getEntityCustomName(Entity entity) {
        Object nameObj = entity.getMetadata().get("custom_name");
        if (nameObj instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /**
     * 获取玩家名。通过 entity UUID 在 Bot.players 缓存中查找。
     */
    private String getPlayerName(Entity playerEntity) {
        // 从 metadata 的 name 取（通常是无颜色格式的显示名）
        Object nameObj = playerEntity.getMetadata().get("name");
        if (nameObj instanceof String name && !name.isEmpty()) {
            return name;
        }
        // 回退到 custom_name
        nameObj = playerEntity.getMetadata().get("custom_name");
        if (nameObj instanceof String name && !name.isEmpty()) {
            return name;
        }
        // 通过 UUID 在 Bot.players 缓存中查找玩家名
        try {
            java.util.UUID uuid = playerEntity.getUuid();
            if (uuid != null) {
                var profile = xin.bbtt.mcbot.Bot.INSTANCE.players.get(uuid);
                if (profile != null) {
                    return profile.getName();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 就近搜索敌对实体（排除玩家和非敌对实体如物品展示框、画等）
     */
    private Entity findNearestHostile(double range) {
        Vector3d playerPos = MovementSync.INSTANCE.position.get();
        if (playerPos == null) return null;

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : MovementSync.INSTANCE.getWorld().getEntities().values()) {
            if (entity.getEntityId() == MovementSync.INSTANCE.entityId) continue;

            String typeName = entity.getType().name().toUpperCase();
            // 跳过玩家和非敌对类型
            if (typeName.equals("PLAYER") || NON_HOSTILE_TYPES.contains(typeName)) continue;

            Vector3d entityPos = entity.getPosition();
            if (entityPos == null) continue;

            double dist = playerPos.distance(entityPos);
            if (dist <= range && dist < nearestDist) {
                nearestDist = dist;
                nearest = entity;
            }
        }
        return nearest;
    }

    private void tick() {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        Vector3d playerPos = MovementSync.INSTANCE.position.get();
        if (playerPos == null) return;

        ActionManager actionManager = SurvivalPlugin.INSTANCE.getActionManager();
        var weapons = SurvivalPlugin.INSTANCE.getConfigManager().getKillAuraWeapons();

        Iterator<Map.Entry<Integer, Long>> it = chasingEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> entry = it.next();
            int entityId = entry.getKey();
            long startTime = entry.getValue();

            // ---------- 目标消失：取消追击 ----------
            Entity target = MovementSync.INSTANCE.getWorld().getEntity(entityId);
            if (target == null) {
                it.remove();
                lastAttackTime.remove(entityId);
                attackFailCount.remove(entityId);
                log.info("[EndermanManager] 目标#{}消失，取消追击", entityId);
                continue;
            }

            // ---------- 超距：取消追击 ----------
            Vector3d targetPos = target.getPosition();
            if (targetPos == null) {
                it.remove();
                lastAttackTime.remove(entityId);
                log.info("[EndermanManager] 目标#{}位置无效，取消追击", entityId);
                continue;
            }

            double dist = playerPos.distance(targetPos);
            double attackRange = SurvivalPlugin.INSTANCE.getConfigManager().getEndermanAttackRange();
            if (dist > attackRange) {
                // 目标超出范围则移除（下次被攻击时重新加入追击列表）
                it.remove();
                lastAttackTime.remove(entityId);
                attackFailCount.remove(entityId);
                log.info("[EndermanManager] 目标#{}超出范围({}格)，取消追击", entityId, String.format("%.1f", dist));
                continue;
            }

            // 冷却检查
            Long lastAtk = lastAttackTime.get(entityId);
            if (lastAtk != null && now - lastAtk < ATTACK_COOLDOWN) continue;

            // 血量低时跳过
            float health = SurvivalPlugin.INSTANCE.getPacketListener().getHealth();
            if (health < 18.0f) continue;

            // 动作管理器权限
            if (!actionManager.tryAcquire(ActionManager.ActionType.ATTACK)) continue;

            try {
                // ---------- 1. 先选武器（所有日志在攻击动作之后输出） ----------
                int bestSlot = SurvivalPlugin.INSTANCE.getKillAura().selectBestWeapon(weapons);

                InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
                if (bestSlot >= 0 && inv != null) {
                    inv.switchToSlot(bestSlot);
                }

                // 获取武器中文名（用于日志）
                String weaponName = getWeaponTypeName();

                // ---------- 2. 转向目标 ----------
                Vector3d targetCenter = new Vector3d(
                    target.getPosition().x,
                    target.getPosition().y + target.getHeight() / 2.0,
                    target.getPosition().z
                );
                MovementSync.INSTANCE.directLookAt(targetCenter);

                // 保持位置并转向
                var session = xin.bbtt.mcbot.Bot.INSTANCE.getSession();
                if (session != null) {
                    session.send(new ServerboundMovePlayerPosRotPacket(
                            MovementSync.INSTANCE.onGround.get(),
                            false,
                            playerPos.x,
                            playerPos.y,
                            playerPos.z,
                            MovementSync.INSTANCE.yaw.get(),
                            MovementSync.INSTANCE.pitch.get()
                    ));
                }

                // 等待 50ms
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}

                // ---------- 3. 攻击并挥臂 ----------
                if (session != null) {
                    session.send(new ServerboundInteractPacket(
                            entityId, InteractAction.ATTACK, Hand.MAIN_HAND, false
                    ));
                    session.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
                }

                // 成功攻击 → 重置失败计数
                attackFailCount.remove(entityId);
                lastAttackTime.put(entityId, now);

                // ---------- 3. 所有动作完成后输出日志 ----------
                String targetType = target.getType().name().toLowerCase();
                if (targetType.equals("player")) {
                    String playerName = getPlayerName(target);
                    log.info("[EndermanManager] 反击玩家: {}, 武器: {}",
                        playerName != null ? playerName : "?", weaponName);
                } else {
                    log.info("[EndermanManager] 反击怪物: {}, 武器: {}",
                        targetType, weaponName);
                }
            } finally {
                if (actionManager != null) {
                    actionManager.release(ActionManager.ActionType.ATTACK);
                }
            }
        }
    }
}
