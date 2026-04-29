package com.example.survival.features;

import com.example.survival.SurvivalPlugin;
import com.example.survival.utils.ConfigManager;
import xin.bbtt.MovementSync;
import xin.bbtt.Entity.Entity;
import xin.bbtt.inventory.InventoryManager;
import xin.bbtt.inventory.ItemRegistry;
import xin.bbtt.mcbot.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class KillAura {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");
    private static final double ALERT_DISTANCE = 7.0;

    private volatile boolean enabled = false;
    private ScheduledExecutorService scheduler;
    private long lastAlertTime = 0;

    public void enable() {
        if (enabled) return;
        enabled = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KillAura-Tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 1500, 1500, TimeUnit.MILLISECONDS);
        log.info("[KillAura] KillAura enabled (1.5s 更新)");
    }

    public void disable() {
        enabled = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("[KillAura] KillAura disabled");
    }

    public boolean isEnabled() { return enabled; }

    private void tick() {
        try {
        if (!enabled) return;

        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        double radius = cfg.getKillAuraRadius();
        List<String> monsters = cfg.getKillAuraMonsters();
        List<String> friends = cfg.getFriends();

        Vector3d playerPos = MovementSync.INSTANCE.position.get();
        if (playerPos == null) return;

        var world = MovementSync.INSTANCE.getWorld();
        if (world == null) return;
        var entities = world.getEntities();
        if (entities.isEmpty()) return;

        List<Entity> targets = new ArrayList<>();
        for (Entity entity : entities.values()) {
            if (entity.getEntityId() == MovementSync.INSTANCE.entityId) continue;

            Vector3d entityPos = entity.getPosition();
            if (entityPos == null) continue;

            double dist = playerPos.distance(entityPos);
            if (dist > radius) continue;

            String entityTypeName = entity.getType().name().toUpperCase();

            // 排除掉落物品
            if (entityTypeName.contains("ITEM_STACK") || entityTypeName.contains("DROPPED")) {
                continue;
            }

            // 排除末影人
            if (entityTypeName.contains("ENDERMAN")) {
                continue;
            }

            boolean isPlayer = entityTypeName.contains("PLAYER");
            if (isPlayer) {
                if (!cfg.isKillAuraAttackPlayers()) {
                    continue;
                }
                String playerName = getEntityName(entity);
                if (playerName != null && !playerName.isBlank()) {
                    String normalizedName = playerName.trim();
                    boolean isFriend = false;
                    for (String friend : friends) {
                        if (friend.trim().equalsIgnoreCase(normalizedName)) {
                            isFriend = true;
                            break;
                        }
                    }
                    if (isFriend) {
                        continue; // 好友跳过
                    }
                }
            } else {
                boolean isTarget = false;
                for (String monster : monsters) {
                    if (entityTypeName.contains(monster) || monster.contains(entityTypeName)) {
                        isTarget = true;
                        break;
                    }
                }
                if (!isTarget) {
                    continue;
                }
            }

            targets.add(entity);
        }

        if (targets.isEmpty()) return;

        targets.sort(Comparator.comparingDouble(e -> playerPos.distance(e.getPosition())));

        int bestSlot = selectBestWeapon(cfg.getKillAuraWeapons());

        for (Entity target : targets) {
            double dist = playerPos.distance(target.getPosition());
            String entityTypeName = target.getType().name();
            String targetName;
            if (entityTypeName.contains("PLAYER")) {
                targetName = getEntityName(target);
                if (targetName == null || targetName.isBlank()) targetName = "PLAYER";
            } else {
                targetName = entityTypeName;
            }

            String weaponStr = bestSlot >= 0 ? "slot" + bestSlot : "fist";
            String alertMark = dist <= ALERT_DISTANCE ? " [警报]" : "";

            log.info("[KillAura] 发现目标: {}, 距离: {}, id: {}, 武器: {}{}",
                    targetName,
                    String.format("%.1f", dist),
                    target.getEntityId(),
                    weaponStr,
                    alertMark);
        }

        // ---------- 攻击逻辑（保持原有逻辑） ----------
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (cfg.getKillAuraCooldown() * 1000);
        if (now - lastAlertTime < cooldownMs) return;

        int maxTargets = cfg.getKillAuraMaxTargets();
        int attackCount = Math.min(maxTargets, targets.size());

        for (int i = 0; i < attackCount; i++) {
            Entity target = targets.get(i);
            double dist = playerPos.distance(target.getPosition());

            if (bestSlot >= 0) {
                MovementSync.INSTANCE.getInventoryManager().switchToSlot(bestSlot);
            }

            Vector3d targetCenter = new Vector3d(
                    target.getPosition().x,
                    target.getPosition().y + target.getHeight() / 2.0,
                    target.getPosition().z
            );

            MovementSync.INSTANCE.directLookAt(targetCenter);

            Bot.INSTANCE.getSession().send(new ServerboundMovePlayerPosRotPacket(
                    MovementSync.INSTANCE.onGround.get(),
                    false,
                    MovementSync.INSTANCE.position.get().x,
                    MovementSync.INSTANCE.position.get().y,
                    MovementSync.INSTANCE.position.get().z,
                    MovementSync.INSTANCE.yaw.get(),
                    MovementSync.INSTANCE.pitch.get()
            ));

            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            Bot.INSTANCE.getSession().send(new ServerboundInteractPacket(
                    target.getEntityId(), InteractAction.ATTACK, Hand.MAIN_HAND, false
            ));

            Bot.INSTANCE.getSession().send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }

        lastAlertTime = now;
        } catch (Exception e) {
            log.error("[KillAura] tick error: {}", e.getMessage(), e);
        }
    }

    public int selectBestWeapon(List<String> weaponOrder) {
        InventoryManager inv = MovementSync.INSTANCE.getInventoryManager();
        var inventory = inv.getInventory();
        if (inventory == null) return -1;

        for (String weaponType : weaponOrder) {
            if (weaponType.equalsIgnoreCase("HAND")) continue;
            int hotbarSlot = findWeaponInHotbar(inventory, weaponType);
            if (hotbarSlot >= 0) return hotbarSlot;
        }

        for (String weaponType : weaponOrder) {
            if (weaponType.equalsIgnoreCase("HAND")) continue;
            int backpackSlot = findWeaponInBackpack(inventory, weaponType);
            if (backpackSlot >= 0) {
                int targetHotbarSlot = inv.getHeldSlot();
                inv.setSlot(inv.getCurrentContainerId(), 36 + targetHotbarSlot, inventory[backpackSlot]);
                return targetHotbarSlot;
            }
        }

        return -1;
    }

    private int findWeaponInHotbar(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[] inventory, String weaponType) {
        for (int slot = 0; slot < 9; slot++) {
            int invSlot = 36 + slot;
            if (invSlot >= inventory.length) continue;
            var item = inventory[invSlot];
            if (item == null) continue;
            if (isWeaponMatch(item, weaponType)) return slot;
        }
        return -1;
    }

    private int findWeaponInBackpack(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[] inventory, String weaponType) {
        for (int invSlot = 9; invSlot < 36; invSlot++) {
            if (invSlot >= inventory.length) continue;
            var item = inventory[invSlot];
            if (item == null) continue;
            if (isWeaponMatch(item, weaponType)) return invSlot;
        }
        return -1;
    }

    private boolean isWeaponMatch(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack item, String weaponType) {
        var entry = ItemRegistry.Instance.getItem(item.getId());
        if (entry == null) return false;
        String itemName = entry.getName();
        if (itemName == null) return false;
        return itemName.toUpperCase().contains(weaponType.toUpperCase());
    }

    /**
     * 获取实体对应的玩家名字。
     * 优先通过 Bot.players 缓存（真实在线玩家名单）获取；
     * 回退方案：从 metadata 中找非实体类型名的字符串字段。
     */
    private String getEntityName(Entity entity) {
        try {
            UUID uuid = entity.getUuid();
            if (uuid != null) {
                var profile = Bot.INSTANCE.players.get(uuid);
                if (profile != null && profile.getName() != null) {
                    return profile.getName();
                }
            }
            var metadata = entity.getMetadata();
            if (metadata == null || metadata.isEmpty()) return null;
            String entityTypeName = entity.getType().name();
            for (var entry : metadata.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof String) {
                    String name = ((String) val).trim();
                    if (name.isBlank()) continue;
                    if (name.equalsIgnoreCase(entityTypeName)) continue;
                    return name;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
