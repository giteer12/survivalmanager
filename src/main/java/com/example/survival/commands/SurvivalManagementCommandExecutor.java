package com.example.survival.commands;

import com.example.survival.SurvivalPlugin;
import com.example.survival.features.KillAura;
import com.example.survival.features.EndermanManager;
import com.example.survival.features.AutoEat;
import com.example.survival.features.AntiAFK;
import com.example.survival.features.InventoryFeature;
import com.example.survival.features.ChestScanner;
import com.example.survival.utils.ConfigManager;
import com.example.survival.utils.ContainerDataManager;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;
import xin.bbtt.mcbot.command.SubCommandExecutor;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SurvivalManagementCommandExecutor extends SubCommandExecutor {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    public SurvivalManagementCommandExecutor() {
        registerSubCommand("help", new CommandExecutor() {
            @Override
            public void onCommand(Command command, String label, String[] args) {
                printHelp();
            }
        });

        registerSubCommand("status", new CommandExecutor() {
            @Override
            public void onCommand(Command command, String label, String[] args) {
                printStatus();
            }
        });

        registerSubCommand("killaura", new SimpleToggleCommand("killaura", "杀戮光环"));
        registerSubCommand("avoidlookat", new SimpleToggleCommand("avoidlookat", "避免看向末影人"));
        registerSubCommand("lookat", new SimpleToggleCommand("lookat", "看向末影人"));
        registerSubCommand("retaliate", new SimpleToggleCommand("retaliate", "末影人反击"));
        registerSubCommand("autoeat", new SimpleToggleCommand("autoeat", "自动进食"));
        registerSubCommand("equip", new SimpleToggleCommand("betterArmorAutoEquip", "自动穿戴更好的装备"));
        registerSubCommand("inventory", new CommandExecutor() {
            @Override
            public void onCommand(Command command, String label, String[] args) {
                if (args.length == 0) {
                    // 默认行为：开关整个背包管理
                    new SimpleToggleCommand("inventory", "背包管理").onCommand(command, label, args);
                    return;
                }
                String sub = args[0].toLowerCase();
                InventoryFeature inv = SurvivalPlugin.INSTANCE.getInventoryManager();
                switch (sub) {
                    case "on" -> {
                        inv.enable();
                        log.info("[Inventory] ✅ 背包管理已启用");
                    }
                    case "off" -> {
                        inv.disable();
                        log.info("[Inventory] ❌ 背包管理已禁用");
                    }
                    case "view" -> inv.printInventoryView();
                    case "refill", "autorefill" -> handleInventorySubToggle(args, "autoRefill", "快捷栏自动补充");
                    case "mending", "repair" -> handleInventorySubToggle(args, "mending", "经验修补");
                    case "drop", "autodrop" -> handleInventorySubToggle(args, "autoDrop", "自动丢弃");
                    default -> {
                        log.info("inventory 子命令:");
                        log.info("  on/off         - 开关整个背包管理");
                        log.info("  view           - 查看背包物品");
                        log.info("  refill [on/off]   - 快捷栏自动补充");
                        log.info("  mending [on/off]  - 经验修补");
                        log.info("  drop [on/off]     - 自动丢弃");
                    }
                }
            }

            @Override
            public List<String> onTabComplete(Command command, String label, String[] args) {
                if (args.length == 1) return List.of("on", "off", "view", "refill", "mending", "drop");
                if (args.length == 2 && (args[0].equals("on") || args[0].equals("off"))) return List.of();
                if (args.length == 2) return List.of("on", "off");
                return Collections.emptyList();
            }
        });
        registerSubCommand("antiafk", new SimpleToggleCommand("antiafk", "防挂机"));
        registerSubCommand("scanner", new ChestScannerCommand());

        registerSubCommand("chest", new CommandExecutor() {
            @Override
            public void onCommand(Command command, String label, String[] args) {
                handleChest(args);
            }

            @Override
            public List<String> onTabComplete(Command command, String label, String[] args) {
                if (args.length == 1) return List.of("save", "load", "delete", "list", "stats");
                if (args.length == 2 && args[0].equalsIgnoreCase("list")) return List.of("overworld", "nether", "the_end");
                return Collections.emptyList();
            }

            @Override
            public AttributedStyle[] onHighlight(Command command, String label, String[] args) {
                AttributedStyle[] styles = new AttributedStyle[args.length];
                if (args.length > 0) {
                    String a = args[0].toLowerCase();
                    if (a.equals("save") || a.equals("load") || a.equals("delete") || a.equals("list") || a.equals("stats")) {
                        styles[0] = new AttributedStyle().foreground(AttributedStyle.GREEN);
                    }
                }
                return styles;
            }
        });

        registerSubCommand("friend", new CommandExecutor() {
            @Override
            public void onCommand(Command command, String label, String[] args) {
                handleFriend(args);
            }

            @Override
            public List<String> onTabComplete(Command command, String label, String[] args) {
                if (args.length == 1) return List.of("add", "remove", "list");
                return Collections.emptyList();
            }

            @Override
            public AttributedStyle[] onHighlight(Command command, String label, String[] args) {
                AttributedStyle[] styles = new AttributedStyle[args.length];
                if (args.length > 0) {
                    String a = args[0].toLowerCase();
                    if (a.equals("add") || a.equals("remove") || a.equals("list")) {
                        styles[0] = new AttributedStyle().foreground(AttributedStyle.GREEN);
                    }
                }
                return styles;
            }
        });
    }

    @Override
    protected void onNoSubCommand(Command command, String label) {
        printHelp();
    }

    private void printHelp() {
        log.info("===== SurvivalManager 帮助 =====");
        log.info("所有命令前缀: survival-management (别名: sm)");
        log.info("");
        log.info("基础命令:");
        log.info("  help         - 显示此帮助");
        log.info("  status       - 查看各功能状态");
        log.info("");
        log.info("功能控制 (on/off):");
        log.info("  killaura     - 杀戮光环");
        log.info("  autoeat      - 自动进食");
        log.info("  inventory [view] - 背包管理 (inventory view = 查看背包)");
        log.info("  antiafk      - 防挂机");
        log.info("");
        log.info("好友管理:");
        log.info("  friend add <玩家名>    - 添加好友");
        log.info("  friend remove <玩家名> - 移除好友");
        log.info("  friend list            - 列出好友");
        log.info("");
        log.info("箱子数据管理:");
        log.info("  chest save <维度> <x> <y> <z> <数据>  - 保存箱子数据");
        log.info("  chest load <维度> <x> <y> <z>         - 读取箱子数据");
        log.info("  chest delete <维度> <x> <y> <z>       - 删除箱子数据");
        log.info("  chest list <维度>                     - 列出维度箱子");
        log.info("  chest stats                           - 统计箱子数量");
        log.info("");
        log.info("箱子自动扫描:");
        log.info("  scanner start/stop    - 开始/停止扫描");
        log.info("  scanner info            - 查看状态");
        log.info("  scanner reset           - 清除已扫描记录");
        log.info("  scanner range <格数>    - 设置扫描半径");
        log.info("  scanner height <min> <max>   - 设置连续高度范围");
        log.info("  scanner heightlayer <y1...>  - 设置离散高度层");
        log.info("  scanner stats           - 查看容器统计");
        log.info("  scanner list <维度>     - 列出已扫描箱子");
        log.info("=================================");
    }

    private void printStatus() {
        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        log.info("===== SurvivalManager 状态 =====");
        log.info("杀戮光环: {} (配置: {})",
                SurvivalPlugin.INSTANCE.getKillAura().isEnabled() ? "运行中" : "已停止",
                cfg.isKillAuraEnabled() ? "启用" : "禁用");
        log.info("自动进食: {} (配置: {})",
                SurvivalPlugin.INSTANCE.getAutoEat().isEnabled() ? "运行中" : "已停止",
                cfg.isAutoEatEnabled() ? "启用" : "禁用");
        log.info("背包管理: {} (配置: {})",
                SurvivalPlugin.INSTANCE.getInventoryManager().isEnabled() ? "运行中" : "已停止",
                cfg.isInventoryManagerEnabled() ? "启用" : "禁用");
        log.info("防挂机: {} (配置: {})",
                SurvivalPlugin.INSTANCE.getAntiAFK().isEnabled() ? "运行中" : "已停止",
                cfg.isAntiAfkEnabled() ? "启用" : "禁用");
        log.info("好友数量: {}", cfg.getFriends().size());
        log.info("");
        log.info("箱子数据:");
        Map<String, Integer> chestStats = SurvivalPlugin.INSTANCE.getChestDataManager().getChestStats();
        chestStats.forEach((dim, count) -> log.info("  {}: {} 个", dim, count));
        log.info("================================");
    }

    private void handleFriend(String[] args) {
        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();

        if (args.length == 0) {
            printFriendList();
            return;
        }

        String action = args[0].toLowerCase();
        switch (action) {
            case "add" -> {
                if (args.length < 2) {
                    log.info("用法: survival-management friend add <玩家名>");
                    return;
                }
                String name = args[1];
                if (cfg.addFriend(name)) {
                    log.info("已添加好友: {}", name);
                } else {
                    log.info("该玩家已在好友列表中");
                }
            }
            case "remove", "del", "delete" -> {
                if (args.length < 2) {
                    log.info("用法: survival-management friend remove <玩家名>");
                    return;
                }
                String name = args[1];
                if (cfg.removeFriend(name)) {
                    log.info("已移除好友: {}", name);
                } else {
                    log.info("该玩家不在好友列表中");
                }
            }
            case "list" -> printFriendList();
            default -> log.info("用法: survival-management friend [add|remove|list] <玩家名>");
        }
    }

    private void printFriendList() {
        List<String> friends = SurvivalPlugin.INSTANCE.getConfigManager().getFriends();
        if (friends.isEmpty()) {
            log.info("好友列表为空");
            log.info("使用 survival-management friend add <玩家名> 添加");
            return;
        }
        log.info("===== 好友列表 =====");
        for (int i = 0; i < friends.size(); i++) {
            log.info("  {}. {}", i + 1, friends.get(i));
        }
        log.info("====================");
    }

    // ====== 箱子数据管理 ======

    private void handleChest(String[] args) {
        if (args.length == 0) {
            log.info("用法: survival-management chest [save|load|delete|list|stats] ...");
            return;
        }

        String action = args[0].toLowerCase();
        var chestManager = SurvivalPlugin.INSTANCE.getChestDataManager();

        switch (action) {
            case "save" -> {
                if (args.length < 6) {
                    log.info("用法: survival-management chest save <维度> <x> <y> <z> <数据>");
                    return;
                }
                String dimension = args[1].toLowerCase();
                try {
                    int x = Integer.parseInt(args[2]);
                    int y = Integer.parseInt(args[3]);
                    int z = Integer.parseInt(args[4]);
                    StringBuilder data = new StringBuilder();
                    for (int i = 5; i < args.length; i++) {
                        if (i > 5) data.append(" ");
                        data.append(args[i]);
                    }
                    chestManager.saveChestData(dimension, x, y, z, data.toString());
                    log.info("已保存箱子数据: {} at [{}, {}, {}]", dimension, x, y, z);
                } catch (NumberFormatException e) {
                    log.info("坐标必须是整数");
                }
            }
            case "load" -> {
                if (args.length < 5) {
                    log.info("用法: survival-management chest load <维度> <x> <y> <z>");
                    return;
                }
                String dimension = args[1].toLowerCase();
                try {
                    int x = Integer.parseInt(args[2]);
                    int y = Integer.parseInt(args[3]);
                    int z = Integer.parseInt(args[4]);
                    String data = chestManager.loadChestData(dimension, x, y, z);
                    if (data != null) {
                        log.info("箱子数据: {} at [{}, {}, {}]", dimension, x, y, z);
                        log.info("内容: {}", data);
                    } else {
                        log.info("未找到箱子数据: {} at [{}, {}, {}]", dimension, x, y, z);
                    }
                } catch (NumberFormatException e) {
                    log.info("坐标必须是整数");
                }
            }
            case "delete" -> {
                if (args.length < 5) {
                    log.info("用法: survival-management chest delete <维度> <x> <y> <z>");
                    return;
                }
                String dimension = args[1].toLowerCase();
                try {
                    int x = Integer.parseInt(args[2]);
                    int y = Integer.parseInt(args[3]);
                    int z = Integer.parseInt(args[4]);
                    boolean deleted = chestManager.deleteChestData(dimension, x, y, z);
                    log.info(deleted ? "已删除箱子数据" : "未找到箱子数据");
                } catch (NumberFormatException e) {
                    log.info("坐标必须是整数");
                }
            }
            case "list" -> {
                if (args.length < 2) {
                    log.info("用法: survival-management chest list <维度>");
                    return;
                }
                String dimension = args[1].toLowerCase();
                var entries = chestManager.listChests(dimension);
                if (entries.isEmpty()) {
                    log.info("维度 {} 没有箱子数据", dimension);
                    return;
                }
                log.info("===== {} 的箱子数据 =====", dimension);
                for (var entry : entries) {
                    log.info("  {} ({}kB)", entry.hash(), String.format("%.1f", entry.size() / 1024.0));
                }
                log.info("共 {} 个箱子", entries.size());
            }
            case "stats" -> {
                Map<String, Integer> stats = chestManager.getChestStats();
                log.info("===== 箱子数据统计 =====");
                int total = 0;
                for (var entry : stats.entrySet()) {
                    log.info("  {}: {} 个", entry.getKey(), entry.getValue());
                    total += entry.getValue();
                }
                log.info("总计: {} 个", total);
                log.info("=======================");
            }
            default -> log.info("用法: survival-management chest [save|load|delete|list|stats] ...");
        }
    }

    // ====== 简单的开关命令封装 ======

    private class SimpleToggleCommand extends CommandExecutor {
        private final String featureName;
        private final String displayName;

        SimpleToggleCommand(String featureName, String displayName) {
            this.featureName = featureName;
            this.displayName = displayName;
        }

        @Override
        public void onCommand(Command command, String label, String[] args) {
            ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();

            if (args.length == 0) {
                boolean current = getCurrentState();
                log.info("{}当前状态: {}", displayName, current ? "开启" : "关闭");
                return;
            }

            String action = args[0].toLowerCase();
            switch (action) {
                case "on", "enable", "true" -> {
                    setConfigState(true);
                    enableFeature();
                    log.info("{}已开启！", displayName);
                }
                case "off", "disable", "false" -> {
                    setConfigState(false);
                    disableFeature();
                    log.info("{}已关闭", displayName);
                }
                default -> log.info("用法: survival-management {} [on|off]", featureName);
            }
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            if (args.length == 1) return List.of("on", "off");
            return Collections.emptyList();
        }

        @Override
        public AttributedStyle[] onHighlight(Command command, String label, String[] args) {
            AttributedStyle[] styles = new AttributedStyle[args.length];
            if (args.length > 0) {
                String a = args[0].toLowerCase();
                if (a.equals("on") || a.equals("off")) {
                    styles[0] = new AttributedStyle().foreground(AttributedStyle.GREEN);
                }
            }
            return styles;
        }

        private boolean getCurrentState() {
            return switch (featureName) {
                case "killaura" -> SurvivalPlugin.INSTANCE.getKillAura().isEnabled();
                case "avoidlookat" -> SurvivalPlugin.INSTANCE.getConfigManager().isEndermanAvoidLookAt();
                case "lookat" -> SurvivalPlugin.INSTANCE.getConfigManager().isEndermanLookAt();
                case "retaliate" -> SurvivalPlugin.INSTANCE.getConfigManager().isRetaliateKillIfAttacked();
                case "autoeat" -> SurvivalPlugin.INSTANCE.getAutoEat().isEnabled();
                case "inventory" -> SurvivalPlugin.INSTANCE.getInventoryManager().isEnabled();
                case "antiafk" -> SurvivalPlugin.INSTANCE.getAntiAFK().isEnabled();
                case "betterArmorAutoEquip" -> SurvivalPlugin.INSTANCE.getConfigManager().isBetterArmorAutoEquipEnabled();
                default -> false;
            };
        }

        private void setConfigState(boolean enabled) {
            switch (featureName) {
                case "killaura" -> SurvivalPlugin.INSTANCE.getConfigManager().setKillAuraEnabled(enabled);
                case "avoidlookat" -> SurvivalPlugin.INSTANCE.getConfigManager().setEndermanAvoidLookAt(enabled);
                case "lookat" -> SurvivalPlugin.INSTANCE.getConfigManager().setEndermanLookAt(enabled);
                case "retaliate" -> SurvivalPlugin.INSTANCE.getConfigManager().setRetaliateKillIfAttacked(enabled);
                case "autoeat" -> SurvivalPlugin.INSTANCE.getConfigManager().setAutoEatEnabled(enabled);
                case "inventory" -> SurvivalPlugin.INSTANCE.getConfigManager().setInventoryManagerEnabled(enabled);
                case "antiafk" -> SurvivalPlugin.INSTANCE.getConfigManager().setAntiAfkEnabled(enabled);
                case "betterArmorAutoEquip" -> SurvivalPlugin.INSTANCE.getConfigManager().setBetterArmorAutoEquipEnabled(enabled);
            }
        }

        private void enableFeature() {
            switch (featureName) {
                case "killaura" -> SurvivalPlugin.INSTANCE.getKillAura().enable();
                case "avoidlookat", "lookat", "retaliate" -> {} // 只改配置，不单独启用功能
                case "autoeat" -> SurvivalPlugin.INSTANCE.getAutoEat().enable();
                case "inventory" -> SurvivalPlugin.INSTANCE.getInventoryManager().enable();
                case "antiafk" -> SurvivalPlugin.INSTANCE.getAntiAFK().enable();
            }
        }

        private void disableFeature() {
            switch (featureName) {
                case "killaura" -> SurvivalPlugin.INSTANCE.getKillAura().disable();
                case "avoidlookat", "lookat", "retaliate" -> {} // 只改配置，不单独禁用功能
                case "autoeat" -> SurvivalPlugin.INSTANCE.getAutoEat().disable();
                case "inventory" -> SurvivalPlugin.INSTANCE.getInventoryManager().disable();
                case "antiafk" -> SurvivalPlugin.INSTANCE.getAntiAFK().disable();
            }
        }
    }

    // 处理 inventory 子功能开关
    private void handleInventorySubToggle(String[] args, String featureKey, String displayName) {
        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        InventoryFeature inv = SurvivalPlugin.INSTANCE.getInventoryManager();
        
        boolean currentState = switch (featureKey) {
            case "autoRefill" -> cfg.isInventoryAutoRefillEnabled();
            case "mending" -> cfg.isInventoryMendingEnabled();
            case "autoHotbarSwitch" -> cfg.isInventoryAutoHotbarSwitchEnabled();
            case "autoDrop" -> cfg.isInventoryAutoDropEnabled();
            case "betterArmorAutoEquip" -> cfg.isBetterArmorAutoEquipEnabled();
            default -> false;
        };
        
        if (args.length < 2) {
            log.info("{}当前状态: {}", displayName, currentState ? "开启" : "关闭");
            log.info("用法: survival-management inventory {} [on/off]", featureKey.toLowerCase());
            return;
        }
        
        String action = args[1].toLowerCase();
        boolean newState = switch (action) {
            case "on", "enable", "true" -> true;
            case "off", "disable", "false" -> false;
            default -> {
                log.info("未知参数: {}，请使用 on 或 off", action);
                yield currentState;
            }
        };
        
        // 更新配置
        switch (featureKey) {
            case "autoRefill" -> cfg.setInventoryAutoRefillEnabled(newState);
            case "mending" -> cfg.setInventoryMendingEnabled(newState);
            case "autoHotbarSwitch" -> cfg.setInventoryAutoHotbarSwitchEnabled(newState);
            case "autoDrop" -> cfg.setInventoryAutoDropEnabled(newState);
            case "betterArmorAutoEquip" -> cfg.setBetterArmorAutoEquipEnabled(newState);
        }
        
        // 更新运行时状态
        if (newState) {
            inv.enable();
        } else {
            // 如果所有子功能都关了，禁用整个 InventoryFeature
            boolean anyEnabled = cfg.isInventoryAutoRefillEnabled() 
                    || cfg.isInventoryMendingEnabled() 
                    || cfg.isInventoryAutoHotbarSwitchEnabled()
                    || cfg.isInventoryAutoDropEnabled();
            if (!anyEnabled) {
                inv.disable();
            }
        }
        
        if (newState) {
            log.info("[InventoryFeature] ✅ {} ENABLED / 已启用", displayName);
        } else {
            log.info("[InventoryFeature] ❌ {} DISABLED / 已禁用", displayName);
        }
    }

    // ====== 箱子扫描器命令 ======

    private class ChestScannerCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            ChestScanner scanner = SurvivalPlugin.INSTANCE.getChestScanner();
            ContainerDataManager cdm = SurvivalPlugin.INSTANCE.getContainerDataManager();

            if (args.length == 0) {
                log.info("scanner 子命令:");
                log.info("  start/stop   - 开始/停止扫描");
                log.info("  info         - 查看状态");
                log.info("  reset        - 清除已扫描记录");
                log.info("  range <格数>  - 设置扫描半径");
                log.info("  height <min> <max>  - 设置连续高度范围 (如: 64 64 或 -64 320)");
                log.info("  heightlayer <y1> [y2 y3 ...]  - 设置离散高度层 (如: 64 11 -16)");
                log.info("  stats        - 查看容器统计");
                log.info("  list <维度>  - 列出已扫描的箱子");
                return;
            }

            String sub = args[0].toLowerCase();
            switch (sub) {
                case "start" -> {
                    scanner.enable();
                    scanner.startScan();
                }
                case "stop" -> {
                    scanner.stopScan();
                    scanner.disable();
                }
                case "info" -> {
                    log.info("===== 箱子扫描器状态 =====");
                    log.info("  启用: {}", scanner.isEnabled() ? "是" : "否");
                    log.info("  扫描中: {}", scanner.isScanning() ? "是" : "否");
                    log.info("  扫描范围: {} 格", scanner.getScanRange());
                    if (scanner.isDiscreteHeight()) {
                        log.info("  高度模式: 离散层 {}", scanner.getHeightLayers());
                    } else {
                        log.info("  高度范围: [{}, {}]", scanner.getScanMinY(), scanner.getScanMaxY());
                    }
                    log.info("  已扫描: {} 个容器", scanner.getScannedCount());
                    log.info("  队列中: {} 个", scanner.getQueuedCount());
                    log.info("==========================");
                }
                case "reset" -> {
                    scanner.resetScanned();
                }
                case "stats" -> {
                    log.info("===== 容器数据统计 =====");
                    log.info("  箱子 (survival/container/chest/):");
                    var chestStats = cdm.getChestStats();
                    int chestTotal = 0;
                    for (var e : chestStats.entrySet()) {
                        log.info("    {}: {} 个", e.getKey(), e.getValue());
                        chestTotal += e.getValue();
                    }
                    log.info("  箱子总计: {} 个", chestTotal);
                    log.info("  潜影盒记录 (survival/container/shulker/):");
                    var shulkerStats = cdm.getShulkerStats();
                    int shulkerTotal = 0;
                    for (var e : shulkerStats.entrySet()) {
                        log.info("    {}: {} 个", e.getKey(), e.getValue());
                        shulkerTotal += e.getValue();
                    }
                    log.info("  潜影盒总计: {} 条记录", shulkerTotal);
                    log.info("==========================");
                }
                case "list" -> {
                    String dim = args.length > 1 ? args[1].toLowerCase() : "overworld";
                    if (!dim.equals("overworld") && !dim.equals("nether") && !dim.equals("the_end")) {
                        log.info("维度只能是 overworld / nether / the_end");
                        return;
                    }
                    var entries = cdm.listChests(dim);
                    if (entries.isEmpty()) {
                        log.info("维度 {} 没有箱子数据", dim);
                        return;
                    }
                    log.info("===== {} 箱子列表 ({} 个) =====", dim, entries.size());
                    java.text.DecimalFormat df = new java.text.DecimalFormat("#.#");
                    for (var e : entries) {
                        String pos = String.format("[%d,%d,%d]", e.x(), e.y(), e.z());
                        log.info("  {} {}  {}kB{}", pos, e.hash(), df.format(e.size() / 1024.0), e.empty() ? " (空)" : "");
                    }
                }
                case "load" -> {
                    // 临时: 列出所有已扫描的箱子统计
                    log.info("箱子总数: {}", cdm.getChestCount());
                    log.info("潜影盒记录: {}", cdm.getShulkerCount());
                }
                case "range" -> {
                    if (args.length < 2) {
                        log.info("当前扫描范围: {} 格", scanner.getScanRange());
                        log.info("用法: scanner range <格数>");
                        return;
                    }
                    try {
                        int r = Integer.parseInt(args[1]);
                        if (r < 8 || r > 128) {
                            log.info("范围应在 8~128 之间");
                            return;
                        }
                        scanner.setScanRange(r);
                        log.info("扫描范围已设为 {} 格", r);
                    } catch (NumberFormatException e) {
                        log.info("无效数字: {}", args[1]);
                    }
                }
                case "height" -> {
                    if (args.length < 3) {
                        log.info("当前高度范围: [{}, {}]", scanner.getScanMinY(), scanner.getScanMaxY());
                        log.info("用法: scanner height <minY> <maxY>  (如: scanner height 64 64)");
                        log.info("用法: scanner heightlayer <y1> [y2 ...]  (如: heightlayer 64 11 -16)");
                        return;
                    }
                    try {
                        int minY = Integer.parseInt(args[1]);
                        int maxY = Integer.parseInt(args[2]);
                        scanner.setHeightRange(minY, maxY);
                        log.info("高度范围已设为 [{}, {}]", minY, maxY);
                        log.info("重启扫描后生效");
                    } catch (NumberFormatException e) {
                        log.info("无效数字");
                    }
                }
                case "heightlayer" -> {
                    if (args.length < 2) {
                        log.info("当前高度层: {}", scanner.getHeightLayers());
                        log.info("用法: scanner heightlayer <y1> [y2 y3 ...]");
                        log.info("例如: scanner heightlayer 64 11 -16");
                        return;
                    }
                    try {
                        List<Integer> layers = new ArrayList<>();
                        for (int i = 1; i < args.length; i++) {
                            layers.add(Integer.parseInt(args[i]));
                        }
                        scanner.setHeightLayers(layers);
                        log.info("高度层已设为: {}", layers);
                        log.info("重启扫描后生效");
                    } catch (NumberFormatException e) {
                        log.info("无效数字");
                    }
                }
            }
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            if (args.length == 1) return List.of("on", "off", "start", "stop", "info", "reset", "range", "height", "heightlayer", "stats", "list", "load");
            if (args.length == 2 && args[0].equalsIgnoreCase("list")) return List.of("overworld", "nether", "the_end");
            return Collections.emptyList();
        }
    }
}
