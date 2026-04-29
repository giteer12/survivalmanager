package com.example.survival.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ConfigManager {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");
    private Map<String, Object> config;
    private Map<String, Object> friendsConfig;
    private final Path configDir;
    private final Path mainConfigPath;
    private final Path friendsConfigPath;

    public ConfigManager() {
        this.configDir = Paths.get("survival", "Config");
        this.mainConfigPath = configDir.resolve("SurvivalManager.yml");
        this.friendsConfigPath = configDir.resolve("Friends.yml");
    }

    public void init() {
        try {
            Files.createDirectories(configDir);

            if (!Files.exists(mainConfigPath)) {
                try (InputStream is = getClass().getClassLoader().getResourceAsStream("config/SurvivalManager.yml")) {
                    if (is != null) {
                        Files.copy(is, mainConfigPath, StandardCopyOption.REPLACE_EXISTING);
                        log.info("[Config] 已创建默认配置文件: {}", mainConfigPath);
                    }
                }
            }
            config = loadYaml(mainConfigPath);

            if (!Files.exists(friendsConfigPath)) {
                try (InputStream is = getClass().getClassLoader().getResourceAsStream("config/Friends.yml")) {
                    if (is != null) {
                        Files.copy(is, friendsConfigPath, StandardCopyOption.REPLACE_EXISTING);
                        log.info("[Config] 已创建默认好友列表: {}", friendsConfigPath);
                    }
                }
            }
            friendsConfig = loadYaml(friendsConfigPath);
        } catch (Exception e) {
            log.error("[Config] 配置加载失败!", e);
            config = new LinkedHashMap<>();
            friendsConfig = new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) {
        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            Map<String, Object> data = yaml.load(reader);
            return data != null ? data : new LinkedHashMap<>();
        } catch (Exception e) {
            log.error("[Config] 读取文件失败: {}", path, e);
            return new LinkedHashMap<>();
        }
    }

    public void saveMainConfig() {
        saveYaml(mainConfigPath, config);
    }

    public void saveFriendsConfig() {
        saveYaml(friendsConfigPath, friendsConfig);
    }

    private void saveYaml(Path path, Map<String, Object> data) {
        Yaml yaml = new Yaml();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            yaml.dump(data, writer);
        } catch (Exception e) {
            log.error("[Config] 保存文件失败: {}", path, e);
        }
    }

    // ====== 安全读取辅助方法 ======

    @SuppressWarnings("unchecked")
    private Object getNested(Map<String, Object> map, String keyPath) {
        String[] keys = keyPath.split("\\.");
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
            if (current == null) return null;
        }
        return current;
    }

    private boolean getBool(Map<String, Object> map, String key, boolean def) {
        Object v = getNested(map, key);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }

    private double getDouble(Map<String, Object> map, String key, double def) {
        Object v = getNested(map, key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        Object v = getNested(map, key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private String getString(Map<String, Object> map, String key, String def) {
        Object v = getNested(map, key);
        if (v instanceof String) return (String) v;
        return def;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object v = getNested(map, key);
        if (v instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) v) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private void setNested(Map<String, Object> map, String keyPath, Object value) {
        String[] keys = keyPath.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = current.get(keys[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<>();
                current.put(keys[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(keys[keys.length - 1], value);
    }

    // ====== KillAura 配置 ======

    public boolean isKillAuraEnabled() { return getBool(config, "killAura.enabled", false); }
    public boolean isKillAuraAttackPlayers() { return getBool(config, "killAura.attackPlayers", false); }
    public double getKillAuraRadius() { return getDouble(config, "killAura.radius", 5.0); }
    public double getKillAuraCooldown() { return getDouble(config, "killAura.cooldown", 1.0); }
    public int getKillAuraMaxTargets() { return getInt(config, "killAura.maxTargets", 2); }
    public List<String> getKillAuraWeapons() { return getStringList(config, "killAura.weapons"); }
    public List<String> getKillAuraMonsters() { return getStringList(config, "killAura.monsters"); }

    public void setKillAuraEnabled(boolean enabled) {
        setNested(config, "killAura.enabled", enabled);
        saveMainConfig();
    }

    // ====== 好友列表 ======

    @SuppressWarnings("unchecked")
    public List<String> getFriends() {
        Object v = friendsConfig.get("friends");
        if (v instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) v) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public boolean addFriend(String name) {
        List<String> friends = new ArrayList<>(getFriends());
        if (friends.contains(name)) return false;
        friends.add(name);
        friendsConfig.put("friends", friends);
        saveFriendsConfig();
        return true;
    }

    @SuppressWarnings("unchecked")
    public boolean removeFriend(String name) {
        List<String> friends = new ArrayList<>(getFriends());
        if (!friends.remove(name)) return false;
        friendsConfig.put("friends", friends);
        saveFriendsConfig();
        return true;
    }

    // ====== EndermanManager 配置 ======

    public boolean isEndermanManagerEnabled() { return getBool(config, "endermanManager.enabled", false); }
    public boolean isEndermanAvoidLookAt() { return getBool(config, "endermanManager.avoidLookAt", true); }
    public boolean isEndermanLookAt() { return getBool(config, "endermanManager.lookAt", false); }
    public double getEndermanAttackRange() { return getDouble(config, "endermanManager.attackRange", 5.0); }

    // ====== Retaliate (反击) 独立模块配置 ======

    public boolean isRetaliateEnabled() { return getBool(config, "retaliate.enabled", true); }
    public boolean isRetaliateKillIfAttacked() { return getBool(config, "retaliate.killIfAttacked", true); }
    public double getRetaliateAttackRange() { return getDouble(config, "retaliate.attackRange", 5.0); }
    public void setRetaliateEnabled(boolean enabled) {
        setNested(config, "retaliate.enabled", enabled);
        saveMainConfig();
    }

    public void setEndermanManagerEnabled(boolean enabled) {
        setNested(config, "endermanManager.enabled", enabled);
        saveMainConfig();
    }

    public void setEndermanAvoidLookAt(boolean enabled) {
        setNested(config, "endermanManager.avoidLookAt", enabled);
        saveMainConfig();
    }

    public void setEndermanLookAt(boolean enabled) {
        setNested(config, "endermanManager.lookAt", enabled);
        saveMainConfig();
    }

    public void setRetaliateKillIfAttacked(boolean enabled) {
        setNested(config, "retaliate.killIfAttacked", enabled);
        saveMainConfig();
    }

    // ====== AutoEat 配置 ======

    public boolean isAutoEatEnabled() { return getBool(config, "autoEat.enabled", false); }
    public double getAutoEatHealthThreshold() { return getDouble(config, "autoEat.healthThreshold", 18.0); }
    public double getAutoEatSaturationThreshold() { return getDouble(config, "autoEat.saturationThreshold", 18.0); }
    public boolean isAutoEatRespectAbsorption() { return getBool(config, "autoEat.respectAbsorption", true); }
    public List<String> getAutoEatPriority() { return getStringList(config, "autoEat.priority"); }
    public List<String> getAutoEatBlacklist() { return getStringList(config, "autoEat.blacklist"); }

    public void setAutoEatEnabled(boolean enabled) {
        setNested(config, "autoEat.enabled", enabled);
        saveMainConfig();
    }

    // ====== InventoryManager 配置 ======

    public boolean isInventoryManagerEnabled() { return getBool(config, "inventoryManager.enabled", false); }
    public List<String> getInventoryAutoDropItems() { return getStringList(config, "inventoryManager.autoDrop"); }
    public int getInventoryDurabilityThreshold() { return getInt(config, "inventoryManager.durabilityAlertThreshold", 10); }
    public int getInventoryAutoDropInterval() { return getInt(config, "inventoryManager.autoDropInterval", 30); }
    public boolean isInventoryAutoDropEnabled() { return getBool(config, "inventoryManager.autoDrop.enabled", false); }
    public void setInventoryManagerEnabled(boolean enabled) {
        setNested(config, "inventoryManager.enabled", enabled);
        saveMainConfig();
    }

    // -- autoRefill --
    public boolean isInventoryAutoRefillEnabled() { return getBool(config, "inventoryManager.autoRefill.enabled", false); }
    public int getInventoryAutoRefillThreshold() { return getInt(config, "inventoryManager.autoRefill.threshold", 20); }
    public List<String> getInventoryAutoRefillItems() { return getStringList(config, "inventoryManager.autoRefill.items"); }
    public List<String> getInventoryAutoRefillAlertItems() { return getStringList(config, "inventoryManager.autoRefill.alertItems"); }
    public void setInventoryAutoRefillEnabled(boolean enabled) {
        setNested(config, "inventoryManager.autoRefill.enabled", enabled);
        saveMainConfig();
    }


    // -- mending --
    public boolean isInventoryMendingEnabled() { return getBool(config, "inventoryManager.mending.enabled", false); }
    public int getInventoryMendingThreshold() { return getInt(config, "inventoryManager.mending.durabilityThreshold", 10); }
    public void setInventoryMendingEnabled(boolean enabled) {
        setNested(config, "inventoryManager.mending.enabled", enabled);
        saveMainConfig();
    }

    // -- autoHotbarSwitch --
    public boolean isInventoryAutoHotbarSwitchEnabled() { return getBool(config, "inventoryManager.autoHotbarSwitch.enabled", false); }
    public int getInventoryAutoHotbarSwitchInterval() { return getInt(config, "inventoryManager.autoHotbarSwitch.interval", 2); }
    public List<String> getInventoryAutoHotbarSwitchItems() { return getStringList(config, "inventoryManager.autoHotbarSwitch.watchItems"); }
    public void setInventoryAutoHotbarSwitchEnabled(boolean enabled) {
        setNested(config, "inventoryManager.autoHotbarSwitch.enabled", enabled);
        saveMainConfig();
    }
    public void setInventoryAutoDropEnabled(boolean enabled) {
        setNested(config, "inventoryManager.autoDrop.enabled", enabled);
        saveMainConfig();
    }

    // -- viewEnabled --
    public boolean isInventoryViewEnabled() { return getBool(config, "inventoryManager.viewEnabled", true); }

    // -- betterArmorAlert --
    public boolean isBetterArmorAlertEnabled() { return getBool(config, "inventoryManager.betterArmorAlert.enabled", true); }
    public void setBetterArmorAlertEnabled(boolean enabled) {
        setNested(config, "inventoryManager.betterArmorAlert.enabled", enabled);
        saveMainConfig();
    }

    // -- betterArmorAutoEquip (自动穿戴更好装备) --
    public boolean isBetterArmorAutoEquipEnabled() { return getBool(config, "inventoryManager.betterArmorAutoEquip.enabled", false); }
    public void setBetterArmorAutoEquipEnabled(boolean enabled) {
        setNested(config, "inventoryManager.betterArmorAutoEquip.enabled", enabled);
        saveMainConfig();
    }

    // -- totem --
    // 获取图腾专用槽位 (0-8)，默认8（最后一个槽位）
    public int getTotemDedicatedSlot() { return getInt(config, "inventoryManager.totem.dedicatedSlot", 8); }
    public void setTotemDedicatedSlot(int slot) {
        setNested(config, "inventoryManager.totem.dedicatedSlot", slot);
        saveMainConfig();
    }

    // -- equipment --
    // 获取装备专用槽位 (0-8)，默认7
    public int getEquipmentDedicatedSlot() { return getInt(config, "inventoryManager.equipment.dedicatedSlot", 7); }
    public void setEquipmentDedicatedSlot(int slot) {
        setNested(config, "inventoryManager.equipment.dedicatedSlot", slot);
        saveMainConfig();
    }

    // -- mending --
    // 获取经验修补专用槽位 (0-8)，默认8
    public int getMendingDedicatedSlot() { return getInt(config, "inventoryManager.mending.dedicatedSlot", 8); }
    public void setMendingDedicatedSlot(int slot) {
        setNested(config, "inventoryManager.mending.dedicatedSlot", slot);
        saveMainConfig();
    }

    // ====== ChestData 配置 ======

    public boolean isChestDataEnabled() { return getBool(config, "chestData.enabled", true); }
    public String getChestDataEncryptionKey() { return getString(config, "chestData.encryptionKey", ""); }

    public void setChestDataEnabled(boolean enabled) {
        setNested(config, "chestData.enabled", enabled);
        saveMainConfig();
    }

    // ====== AntiAFK 配置 ======

    public boolean isAntiAfkEnabled() { return getBool(config, "antiAfk.enabled", false); }
    public int getAntiAfkActionInterval() { return getInt(config, "antiAfk.actionInterval", 180); }
    public List<String> getAntiAfkActions() { return getStringList(config, "antiAfk.actions"); }
    public int getAntiAfkRotateRange() { return getInt(config, "antiAfk.rotateRange", 30); }

    public void setAntiAfkEnabled(boolean enabled) {
        setNested(config, "antiAfk.enabled", enabled);
        saveMainConfig();
    }

    // ====== ChestScanner 配置 ======

    public boolean isChestScannerEnabled() { return getBool(config, "chestScanner.enabled", false); }
    public int getChestScannerRange() { return getInt(config, "chestScanner.scanRange", 32); }
    public int getChestScannerMinY() { return getInt(config, "chestScanner.scanHeight.min", -64); }
    public int getChestScannerMaxY() { return getInt(config, "chestScanner.scanHeight.max", 320); }
    public boolean isChestScannerDiscreteHeight() { return getBool(config, "chestScanner.scanHeight.discrete", false); }
    public List<String> getChestScannerHeightLayers() { return getStringList(config, "chestScanner.scanHeight.layers"); }

    public void setChestScannerEnabled(boolean enabled) {
        setNested(config, "chestScanner.enabled", enabled);
        saveMainConfig();
    }

    public void setChestScannerRange(int range) {
        setNested(config, "chestScanner.scanRange", range);
        saveMainConfig();
    }

    public void setChestScannerMinY(int y) {
        setNested(config, "chestScanner.scanHeight.min", y);
        saveMainConfig();
    }

    public void setChestScannerMaxY(int y) {
        setNested(config, "chestScanner.scanHeight.max", y);
        saveMainConfig();
    }

    public void setChestScannerHeightLayers(List<String> layers) {
        setNested(config, "chestScanner.scanHeight.layers", layers);
        saveMainConfig();
    }

    // ====== NoFall 配置 ======

    public double getNoFallVelocityThreshold() {
        return getDouble(config, "noFall.velocityThreshold", -0.5);
    }
}
