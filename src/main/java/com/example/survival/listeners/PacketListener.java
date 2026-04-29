package com.example.survival.listeners;

import com.example.survival.SurvivalPlugin;
import com.example.survival.features.InventoryFeature;
import xin.bbtt.inventory.ItemRegistry;
import xin.bbtt.mcbot.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.ReceivePacketEvent;
import com.example.survival.features.InventoryFeature;
import com.example.survival.features.KillAura;
import com.example.survival.features.EndermanManager;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.ReceivePacketEvent;
import xin.bbtt.MovementSync;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import xin.bbtt.Entity.EntityRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens to server data packets, provides data for survival features
 */
public class PacketListener implements Listener {

    /** Player health */
    private volatile float health = 20.0f;
    /** Food level */
    private volatile int foodLevel = 20;
    /** Saturation level */
    private volatile float saturationLevel = 20.0f;
    /** Absorption amount */
    private volatile float absorptionAmount = 0.0f;

    // Container stateId cache (for InventoryFeature ClickPacket to ensure stateId sync)
    private static final AtomicInteger LAST_CONTAINER_STATE_ID = new AtomicInteger(0);
    private static final AtomicInteger LAST_CONTAINER_ID = new AtomicInteger(0);

    /** Get latest container stateId */
    public static int getContainerStateId() { return LAST_CONTAINER_STATE_ID.get(); }
    /** Get latest container ID */
    public static int getContainerId() { return LAST_CONTAINER_ID.get(); }

    /** Whether first scan has been done */
    private volatile boolean firstScanDone = false;
    /** Whether first inventory container sync has been processed (Bug-4 fix) */
    private volatile boolean firstInventoryInitialized = false;

    /**
     * On login packet received - entityId is now available, trigger first scan
     */
    @EventHandler
    public void onLogin(ReceivePacketEvent<ClientboundLoginPacket> event) {
        ClientboundLoginPacket packet = event.getPacket();
        int entityId = packet.getEntityId();
        SurvivalPlugin.INSTANCE.getLogger().info("[PacketListener] Received login, entityId={}", entityId);

        if (!firstScanDone) {
            firstScanDone = true;
            // Wait a bit to let inventory data sync
            MovementSync.INSTANCE.movementService.schedule(() -> {
                SurvivalPlugin.INSTANCE.getLogger().info("[PacketListener] First scan triggered (entityId={})", entityId);
                InventoryFeature invFeature = SurvivalPlugin.INSTANCE.getInventoryManager();
                if (invFeature != null && invFeature.isEnabled()) {
                    invFeature.onPlayerLogin();
                }
            }, 2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @EventHandler
    public void onContainerContent(ReceivePacketEvent<ClientboundContainerSetContentPacket> event) {
        ClientboundContainerSetContentPacket packet = event.getPacket();
        LAST_CONTAINER_STATE_ID.set(packet.getStateId());
        LAST_CONTAINER_ID.set(packet.getContainerId());

        // Bug-4 Fix: On first player inventory sync (containerId==0),
        // initialize LAST_OFFHAND_WAS_TOTEM from the actual offhand slot.
        // This handles the case where player logs in already holding a totem
        // in the offhand — without this fix, the flag stays false and the
        // first totem break would NOT trigger replenishment.
        if (packet.getContainerId() == 0 && !firstInventoryInitialized) {
            firstInventoryInitialized = true;
            ItemStack offhandItem = null;
            // Slot 45 is the offhand in the container contents array.
            // The packet's slots array starts at slot 0, so index 45 gives us offhand.
            var items = packet.getItems();
            if (items != null && items.length > 45) {
                offhandItem = items[45];
            }
            if (offhandItem != null) {
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(offhandItem.getId());
                if (entry != null && entry.getName() != null) {
                    String normalized = entry.getName().toLowerCase().replace(" ", "").replace("_", "");
                    if (normalized.equals("totemofundying")) {
                        LAST_OFFHAND_WAS_TOTEM = true;
                        SurvivalPlugin.INSTANCE.getLogger().info("[PacketListener] Initial offhand is totem, flag initialized");
                    }
                }
            }
        }
    }
    
    /** Listen to offhand (slot=45) changes, auto-replenish totem */
    @EventHandler
    public void onContainerSetSlot(ReceivePacketEvent<ClientboundContainerSetSlotPacket> event) {
        ClientboundContainerSetSlotPacket packet = event.getPacket();

        // Bug-3 Fix: Always keep stateId and containerId in sync
        LAST_CONTAINER_STATE_ID.set(packet.getStateId());
        if (packet.getContainerId() == 0) {
            LAST_CONTAINER_ID.set(0);
        }

        // containerId=0 means player inventory
        if (packet.getContainerId() != 0) return;

        int slot = packet.getSlot();

        // Offhand change: trigger totem replenishment
        if (slot == 45) {
            ItemStack item = packet.getItem();

            // Check if currently holding totem
            boolean currentlyTotem = false;
            if (item != null) {
                ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
                if (entry != null) {
                    String name = entry.getName();
                    if (name != null) {
                        String normalized = name.toLowerCase().replace(" ", "").replace("_", "");
                        currentlyTotem = normalized.equals("totemofundying");
                    }
                }
            }

            // Bug-2 Fix: Only replenish when totem is consumed (item == null),
            // not when player manually swaps it for a shield/torch
            if (item == null && LAST_OFFHAND_WAS_TOTEM) {
                SurvivalPlugin.INSTANCE.getLogger().info("[PacketListener] Offhand totem consumed, replenishing");
                ItemStack[] inv = MovementSync.INSTANCE.getInventoryManager().getInventory();
                if (inv != null) {
                    // 事件触发：跳过副手检查，直接搜索背包
                    SurvivalPlugin.INSTANCE.getInventoryManager().checkAndReplenishTotem(inv);
                }
            }

            // Update last state
            LAST_OFFHAND_WAS_TOTEM = currentlyTotem;
            return;
        }

        // Hotbar slot change (slots 36-44): trigger instant refill check
        if (slot >= 36 && slot <= 44) {
            ItemStack item = packet.getItem();
            if (item == null) return;

            // Check if air item
            ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(item.getId());
            if (entry == null) return; // Air items have no registry info

            // Skip non-stackable items (tools, weapons, etc.)
            if (entry.getStackSize() <= 1) return;

            int amount = item.getAmount();
                // Hotbar slot change (slots 36-44): trigger instant refill check is handled by the polling tick
        }
    }

    /** Whether last offhand was totem */
    private static volatile boolean LAST_OFFHAND_WAS_TOTEM = false;

    // Food nutrition values: item -> [hunger_restored, saturation_restored]
    private static final Map<String, float[]> FOOD_VALUES = Map.ofEntries(
        Map.entry("ENCHANTED_GOLDEN_APPLE", new float[]{4, 9.6f}),
        Map.entry("GOLDEN_APPLE", new float[]{4, 9.6f}),
        Map.entry("GOLDEN_CARROT", new float[]{6, 14.4f}),
        Map.entry("HONEY_BOTTLE", new float[]{6, 1.2f}),
        Map.entry("BREAD", new float[]{5, 6.0f}),
        Map.entry("CARROT", new float[]{3, 3.6f}),
        Map.entry("POTATO", new float[]{1, 0.6f}),
        Map.entry("BAKED_POTATO", new float[]{5, 6.0f}),
        Map.entry("BEETROOT", new float[]{1, 1.2f}),
        Map.entry("BEETROOT_SOUP", new float[]{6, 7.2f}),
        Map.entry("APPLE", new float[]{4, 2.4f}),
        Map.entry("MELON_SLICE", new float[]{2, 1.2f}),
        Map.entry("COOKIE", new float[]{2, 0.4f}),
        Map.entry("COOKED_BEEF", new float[]{8, 12.8f}),
        Map.entry("COOKED_PORKCHOP", new float[]{8, 12.8f}),
        Map.entry("COOKED_CHICKEN", new float[]{6, 7.2f}),
        Map.entry("COOKED_MUTTON", new float[]{6, 9.6f}),
        Map.entry("COOKED_COD", new float[]{5, 6.0f}),
        Map.entry("COOKED_SALMON", new float[]{6, 9.6f}),
        Map.entry("COOKED_RABBIT", new float[]{5, 6.0f}),
        Map.entry("MUSHROOM_STEW", new float[]{6, 7.2f}),
        Map.entry("RABBIT_STEW", new float[]{10, 12.0f}),
        Map.entry("SUSPICIOUS_STEW", new float[]{6, 7.2f}),
        Map.entry("PUMPKIN_PIE", new float[]{8, 4.8f}),
        Map.entry("STEAK", new float[]{8, 12.8f})
    );

    @EventHandler
    public void onEntityMetadata(ReceivePacketEvent<ClientboundSetEntityDataPacket> event) {
        ClientboundSetEntityDataPacket packet = event.getPacket();
        int entityId = packet.getEntityId();

        // Update self metadata (health, food, saturation, etc.)
        if (entityId == MovementSync.INSTANCE.entityId) {
            EntityRegistry.EntityEntry entry = EntityRegistry.Instance.getEntity("PLAYER");
            if (entry == null || entry.getMetadataKeys() == null) return;
            List<String> keys = entry.getMetadataKeys();
            for (EntityMetadata<?, ?> meta : packet.getMetadata()) {
                int id = meta.getId();
                if (id >= keys.size()) continue;
                String key = keys.get(id);
                // Bug-5 Fix: skip null keys to avoid NPE in switch
                if (key == null) continue;
                Object value = meta.getValue();
                // Normalize key to lowercase for case-insensitive matching
                String normKey = key.toLowerCase();
                switch (normKey) {
                    case "health" -> {
                        if (value instanceof Float f) health = f;
                        else if (value instanceof Number n) health = n.floatValue();
                    }
                    case "food" -> {
                        if (value instanceof Integer i) foodLevel = i;
                        else if (value instanceof Number n) foodLevel = n.intValue();
                    }
                    case "saturation" -> {
                        if (value instanceof Float f) saturationLevel = f;
                        else if (value instanceof Number n) saturationLevel = n.floatValue();
                    }
                    case "absorption_amount", "absorption" -> {
                        if (value instanceof Float f) absorptionAmount = f;
                        else if (value instanceof Number n) absorptionAmount = n.floatValue();
                    }
                }
                MovementSync.INSTANCE.getSelfMetadata().put(key, value);
            }
        }
    }

    @EventHandler
    public void onDamageEvent(ReceivePacketEvent<ClientboundDamageEventPacket> event) {
        var packet = event.getPacket();
        // Notify damage handler: player was attacked
        int playerEntityId = packet.getEntityId(); // victim is player
        int attackerId = packet.getSourceDirectId(); // attacker entity ID
        SurvivalPlugin.INSTANCE.getEndermanManager().onEntityDamage(attackerId);
    }

    // ====== Getters for survival features ======

    public float getHealth() { return health; }
    public int getFoodLevel() { return foodLevel; }
    public float getSaturationLevel() { return saturationLevel; }
    public float getAbsorptionAmount() { return absorptionAmount; }

    /**
     * Get food nutrition value by item name
     * @return [hunger_restored, saturation_restored], or null if not food
     */
    public static float[] getFoodValue(String itemName) {
        if (itemName == null) return null;
        return FOOD_VALUES.get(itemName.toUpperCase());
    }
}