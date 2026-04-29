package com.example.survival.features;

import com.example.survival.SurvivalPlugin;
import com.example.survival.utils.ConfigManager;
import xin.bbtt.MovementSync;
import xin.bbtt.Block.BlockState;
import xin.bbtt.inventory.ItemRegistry;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.events.ReceivePacketEvent;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.Listener;
import org.joml.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 箱子扫描器
 *
 * 功能：
 * 1. 自动搜索附近未扫描过的箱子(27格)和大箱子(54格)
 * 2. 前往箱子位置 → 右键打开 → 读取内容 → 加密保存 → 关闭
 * 3. 识别潜影盒并记录种类/数量
 * 4. 已扫描位置记忆，不重复处理
 * 排除：末影箱
 */
public class ChestScanner implements Listener {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    private volatile boolean enabled = false;
    private volatile boolean scanning = false;
    private final List<ScanTarget> queue = new CopyOnWriteArrayList<>();
    private final Set<String> scanned = ConcurrentHashMap.newKeySet();
    private volatile ScanTarget currentTarget = null;
    private final AtomicBoolean waitingForContent = new AtomicBoolean(false);
    private volatile int containerId = -1;

    private ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<ScanTarget, int[]> openRetries = new ConcurrentHashMap<>();

    // 已扫描位置持久化
    private final java.nio.file.Path scannedPositionsFile;
    private volatile boolean dirty = false; // 是否有未保存的变更

    private static final int MAX_RANGE = 200;
    private static final int MAX_OPEN_RETRIES = 15;
    private boolean discreteHeight = false;
    private List<Integer> heightLayers = new ArrayList<>();

    public ChestScanner() {
        this.scannedPositionsFile = java.nio.file.Paths.get("survival", "container", "scanned_positions.txt");
    }

    // ====== 可配置参数 ======
    private int scanRange = 32;
    private int scanMinY = -64;
    private int scanMaxY = 320;

    public void loadConfig() {
        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        this.scanRange = cfg.getChestScannerRange();
        this.scanMinY = cfg.getChestScannerMinY();
        this.scanMaxY = cfg.getChestScannerMaxY();
        this.discreteHeight = cfg.isChestScannerDiscreteHeight();
        List<String> layers = cfg.getChestScannerHeightLayers();
        this.heightLayers = new ArrayList<>();
        for (String s : layers) {
            try { heightLayers.add(Integer.parseInt(s.trim())); } catch (Exception ignored) {}
        }
        if (discreteHeight) {
            log.info("[ChestScanner] 高度配置: 离散层 {}", heightLayers);
        } else {
            log.info("[ChestScanner] 高度配置: 范围 [{}, {}]", scanMinY, scanMaxY);
        }
    }

    public void enable() {
        if (enabled) return;
        enabled = true;
        Bot.INSTANCE.getPluginManager().events().registerEvents(this, SurvivalPlugin.INSTANCE);
        log.info("[ChestScanner] ✅ 已启用 (范围={})", scanRange);
    }

    public void disable() {
        enabled = false;
        scanning = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        Bot.INSTANCE.getPluginManager().events().unregisterAll(SurvivalPlugin.INSTANCE);
        queue.clear(); currentTarget = null; waitingForContent.set(false);
        log.info("[ChestScanner] ❌ 已禁用");
    }

    public boolean isEnabled() { return enabled; }
    public boolean isScanning() { return scanning; }

    public void startScan() {
        if (!enabled) { log.info("[ChestScanner] 未启用"); return; }
        if (scanning) { log.info("[ChestScanner] 已在运行"); return; }
        scanning = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChestScanner"); t.setDaemon(true); return t;
        });
        loadConfig();
        // 启动时从硬盘加载已扫描位置
        int loaded = loadScannedPositions();
        log.info("[ChestScanner] 从硬盘加载了 {} 个已扫描位置", loaded);
        scheduler.submit(() -> discoverAndProcess());
        // 定期(5秒)检查新容器
        scheduler.scheduleAtFixedRate(() -> discoverAndProcess(), 5, 5, TimeUnit.SECONDS);
        log.info("[ChestScanner] 🟢 开始扫描 (已加载{}个，队列{})", scanned.size(), queue.size());
    }

    public void stopScan() {
        if (!scanning) { log.info("[ChestScanner] 未在运行"); return; }
        scanning = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        closeContainer();
        queue.clear(); currentTarget = null; waitingForContent.set(false);
        // 停止时保存扫描记录到硬盘
        saveScannedPositions();
        log.info("[ChestScanner] 🔴 已停止 (本次共扫描{}个容器)", scanned.size());
    }

    public void resetScanned() {
        int c = scanned.size();
        scanned.clear();
        dirty = false;
        // 同时删除硬盘上的记录文件
        try {
            java.nio.file.Files.deleteIfExists(scannedPositionsFile);
        } catch (Exception ignored) {}
        log.info("[ChestScanner] 清除{}个容器的扫描记录（含硬盘文件）", c);
    }

    /**
     * 从硬盘加载已扫描位置到内存（去重追加）
     * 文件格式：每行一个位置，格式: x,y,z
     */
    private int loadScannedPositions() {
        if (!java.nio.file.Files.exists(scannedPositionsFile)) return 0;
        int count = 0;
        try {
            java.nio.file.Files.createDirectories(scannedPositionsFile.getParent());
            var lines = java.nio.file.Files.readAllLines(scannedPositionsFile, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (scanned.add(trimmed)) count++;
            }
        } catch (Exception e) {
            log.warn("[ChestScanner] 加载扫描记录失败: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 将内存中的已扫描位置批量写入硬盘（仅在有变更时）
     * 文件格式：每行一个位置，格式: x,y,z
     */
    private void saveScannedPositions() {
        if (!dirty) {
            log.debug("[ChestScanner] 无变更，跳过保存");
            return;
        }
        try {
            java.nio.file.Files.createDirectories(scannedPositionsFile.getParent());
            var lines = new java.util.ArrayList<String>(scanned);
            java.nio.file.Files.write(scannedPositionsFile, lines, java.nio.charset.StandardCharsets.UTF_8);
            dirty = false;
            log.info("[ChestScanner] 已保存{}个位置到硬盘", lines.size());
        } catch (Exception e) {
            log.error("[ChestScanner] 保存扫描记录失败: {}", e.getMessage());
        }
    }

    public int getScannedCount() { return scanned.size(); }
    public int getQueuedCount() { return queue.size(); }

    // ====== 运行时参数 ======

    public int getScanRange() { return scanRange; }
    public int getScanMinY() { return scanMinY; }
    public int getScanMaxY() { return scanMaxY; }
    public boolean isDiscreteHeight() { return discreteHeight; }
    public List<Integer> getHeightLayers() { return heightLayers; }

    public void setScanRange(int range) {
        this.scanRange = range;
        SurvivalPlugin.INSTANCE.getConfigManager().setChestScannerRange(range);
        log.info("[ChestScanner] 扫描范围已设为: {}", range);
    }

    /** 设置连续高度范围 */
    public void setHeightRange(int minY, int maxY) {
        this.scanMinY = minY;
        this.scanMaxY = maxY;
        this.discreteHeight = false;
        this.heightLayers.clear();
        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        cfg.setChestScannerMinY(minY);
        cfg.setChestScannerMaxY(maxY);
        log.info("[ChestScanner] 高度范围已设为: [{}, {}]", minY, maxY);
    }

    /** 设置离散高度层 */
    public void setHeightLayers(List<Integer> layers) {
        this.discreteHeight = true;
        this.heightLayers = new ArrayList<>(layers);
        this.scanMinY = -64;
        this.scanMaxY = 320;
        List<String> strLayers = new ArrayList<>();
        for (int y : layers) strLayers.add(String.valueOf(y));
        SurvivalPlugin.INSTANCE.getConfigManager().setChestScannerHeightLayers(strLayers);
        log.info("[ChestScanner] 高度层已设为: {}", layers);
    }

    // ====== 扫描逻辑 ======

    /** 判断 Y 坐标是否在配置范围内 */
    private boolean shouldCheckY(int y) {
        if (discreteHeight) return heightLayers.contains(y);
        return y >= scanMinY && y <= scanMaxY;
    }

    /** 计算两点间欧几里得距离 */
    private double distTo(int x, int y, int z) {
        Vector3d p = MovementSync.INSTANCE.position.get();
        if (p == null) return Double.MAX_VALUE;
        return Math.sqrt(Math.pow(p.x - x, 2) + Math.pow(p.y - y, 2) + Math.pow(p.z - z, 2));
    }

    private void discoverAndProcess() {
        if (!enabled || !scanning) return;
        try {
            Vector3d pos = MovementSync.INSTANCE.position.get();
            if (pos == null) return;

            int px = (int) Math.floor(pos.x);
            int py = (int) Math.floor(pos.y);
            int pz = (int) Math.floor(pos.z);

            int found = 0;
            // 先扫描水平面，再检查 Y 范围
            for (int x = px - scanRange; x <= px + scanRange; x++) {
                for (int z = pz - scanRange; z <= pz + scanRange; z++) {
                    int dx = x - px, dz = z - pz;
                    if (dx * dx + dz * dz > scanRange * scanRange) continue;

                    if (discreteHeight) {
                        // 离散模式：只扫描指定层
                        for (int y : heightLayers) {
                            BlockState state = MovementSync.INSTANCE.getWorld().getBlockStateAt(new Vector3d(x, y, z));
                            if (state == null) continue;
                            if (!isContainer(state.blockName())) continue;
                            String key = x + "," + y + "," + z;
                            if (scanned.contains(key)) continue;
                            scanned.add(key);
                            dirty = true;
                            queue.add(new ScanTarget(x, y, z));
                            found++;
                        }
                        continue;
                    }

                    // 连续模式：在 scanMinY~scanMaxY 范围内扫描
                    int yStart = Math.max(scanMinY, py - 16);
                    int yEnd = Math.min(scanMaxY, py + 16);
                    for (int y = yStart; y <= yEnd; y++) {
                        if (!shouldCheckY(y)) continue;
                        BlockState state = MovementSync.INSTANCE.getWorld().getBlockStateAt(new Vector3d(x, y, z));
                        if (state == null) continue;
                        if (!isContainer(state.blockName())) continue;

                        String key = x + "," + y + "," + z;
                        if (scanned.contains(key)) continue;
                        scanned.add(key);
                        dirty = true;
                        queue.add(new ScanTarget(x, y, z));
                        found++;
                    }
                }
            }
            if (found > 0) log.info("[ChestScanner] 发现{}个新容器 (队列: {})", found, queue.size());
            if (currentTarget == null && !queue.isEmpty()) processNext();
        } catch (Exception e) {
            log.error("[ChestScanner] 扫描异常: {}", e.getMessage());
        }
    }

    private boolean isContainer(String blockName) {
        if (blockName == null) return false;
        String u = blockName.toUpperCase();
        if (u.contains("ENDER_CHEST")) return false;  // 排除末影箱
        return u.contains("CHEST") || u.contains("TRAPPED_CHEST") || u.contains("BARREL");
    }

    private void processNext() {
        if (!enabled || !scanning || queue.isEmpty()) return;
        // 找离玩家最近的箱子
        ScanTarget nearest = queue.get(0);
        double minDist = distTo(nearest.x, nearest.y, nearest.z);
        for (ScanTarget st : queue) {
            double d = distTo(st.x, st.y, st.z);
            if (d < minDist) { minDist = d; nearest = st; }
        }
        queue.remove(nearest);
        currentTarget = nearest;
        final ScanTarget target = nearest;
        log.info("[ChestScanner] 前往容器 [{},{},{}] (剩余{}个, 最近{}格)", target.x, target.y, target.z, queue.size(), String.format("%.1f", minDist));
        // 使用 MovementSync 寻路：设目标并触发自动寻路
        MovementSync.INSTANCE.setActiveGoal(new org.joml.Vector3i(target.x, target.y, target.z));
        MovementSync.INSTANCE.triggerAutoRepath();
        // 3秒后尝试打开
        scheduler.schedule(() -> tryOpen(target), 500, TimeUnit.MILLISECONDS);
    }

    private void tryOpen(ScanTarget t) {
        if (!enabled || !scanning || currentTarget != t) return;
        Vector3d pp = MovementSync.INSTANCE.position.get();
        if (pp == null) { skip(); return; }
        double dist = Math.sqrt(Math.pow(pp.x - t.x, 2) + Math.pow(pp.y - t.y, 2) + Math.pow(pp.z - t.z, 2));
        if (dist > 3.5) {
            // 检查重试次数
            int[] retries = openRetries.computeIfAbsent(t, k -> new int[]{0});
            retries[0]++;
            if (retries[0] >= MAX_OPEN_RETRIES) {
                log.warn("[ChestScanner] 尝试{}次仍无法靠近容器 [{},{},{}]，跳过", MAX_OPEN_RETRIES, t.x, t.y, t.z);
                skip(); return;
            }
            // 每2秒重新触发寻路，防止 bot 卡住
            MovementSync.INSTANCE.triggerAutoRepath();
            log.info("[ChestScanner] 距离 {} 格，等待靠近...", String.format("%.1f", dist));
            scheduler.schedule(() -> tryOpen(t), 2000, TimeUnit.MILLISECONDS);
            return;
        }
        // 右键打开
        try {
            MovementSync.INSTANCE.directLookAt(new Vector3d(t.x + 0.5, t.y + 0.5, t.z + 0.5));
            Bot.INSTANCE.getSession().send(new ServerboundUseItemOnPacket(
                    Vector3i.from(t.x, t.y, t.z), Direction.UP, Hand.MAIN_HAND,
                    0.5f, 0.5f, 0.5f, false, false, Bot.INSTANCE.getAndIncreaseSequence()));
            log.info("[ChestScanner] 右键打开容器 [{},{},{}]", t.x, t.y, t.z);
            waitingForContent.set(true);
            // 取消导航目标
            MovementSync.INSTANCE.setActiveGoal(null);
            // 5秒超时
            scheduler.schedule(() -> {
                if (waitingForContent.get() && currentTarget == t) {
                    log.info("[ChestScanner] 容器超时，跳过"); closeContainer(); finish();
                }
            }, 5000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("[ChestScanner] 打开失败: {}", e.getMessage()); skip();
        }
    }

    @EventHandler
    public void onContainerOpen(ReceivePacketEvent<ClientboundOpenScreenPacket> e) {
        if (!waitingForContent.get()) return;
        containerId = e.getPacket().getContainerId();
        log.info("[ChestScanner] 📤 收到打开容器包 containerId={}", containerId);
    }

    @EventHandler
    public void onContainerContent(ReceivePacketEvent<ClientboundContainerSetContentPacket> event) {
        if (!waitingForContent.get()) return;
        var pkt = event.getPacket();
        int cid = pkt.getContainerId();
        if (cid == 0) return; // 玩家背包
        if (currentTarget == null) return;

        ItemStack[] items = pkt.getItems();
        int size = items != null ? items.length : 0;
        log.info("[ChestScanner] 🔍 容器内容包: containerId={}, size={}", cid, size);
        if (size != 27 && size != 54) {
            log.info("[ChestScanner] ⚠️ 跳过{}格容器 (非标准箱子/潜影盒)", size);
            waitingForContent.set(false); closeContainer(); finish(); return;
        }

        containerId = cid;
        waitingForContent.set(false);
        ScanTarget t = currentTarget;
        log.info("[ChestScanner] 📦 收到{}格容器内容", size);

        try {
            String dim = "overworld"; // TODO: 获取真实维度
            List<Map<String, Object>> itemList = new ArrayList<>();
            boolean empty = true;
            // 用于日志摘要
            StringBuilder summary = new StringBuilder();
            Map<Integer, List<String>> shulkerContents = new LinkedHashMap<>();
            if (items != null) {
                for (int i = 0; i < items.length; i++) {
                    ItemStack it = items[i];
                    if (it == null || it.getId() == 0) continue;
                    empty = false;
                    var entry = ItemRegistry.Instance.getItem(it.getId());
                    String name = entry != null ? entry.getName() : "UNKNOWN";
                    int count = Math.max(1, it.getAmount());
                    String nameUpper = name.toUpperCase();
                    // 记录潜影盒及其内容
                    if (nameUpper.contains("SHULKER_BOX")) {
                        String disp = getDisplayName(it, name);
                        String shulkerInfo = disp + " x" + count;
                        summary.append(shulkerInfo).append(", ");
                        SurvivalPlugin.INSTANCE.getContainerDataManager()
                            .saveShulkerRecord(dim, t.x, t.y, t.z, i, disp, name, count);
                        // 潜影盒内容需要后续处理
                    }
                    // 普通物品直接加入摘要
                    else {
                        String dispName = (entry != null && entry.getDisplayName() != null) 
                            ? entry.getDisplayName() : name;
                        summary.append(dispName).append("x").append(count).append(", ");
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("slot", i); m.put("id", it.getId()); m.put("name", name); m.put("count", count);
                    // 附加中文显示名
                    if (entry != null && entry.getDisplayName() != null) {
                        m.put("displayName", entry.getDisplayName());
                    }
                    itemList.add(m);
                }
            }
            // 生成日志摘要
            String logSummary = summary.length() > 2 
                ? summary.substring(0, summary.length() - 2) : "(空)";
            String json = itemsToJson(itemList);
            SurvivalPlugin.INSTANCE.getContainerDataManager()
                .saveChestData(dim, t.x, t.y, t.z, json, 0, empty);
            log.info("[ChestScanner] ✅ 容器 [{},{},{}]: {}",
                t.x, t.y, t.z, logSummary);
        } catch (Exception e) {
            log.error("[ChestScanner] 保存失败: {}", e.getMessage());
        }
        closeContainer();
        finish();
    }

    private String getDisplayName(ItemStack item, String fallback) {
        try {
            var comps = item.getDataComponentsPatch();
            if (comps != null) {
                var cn = comps.get(DataComponentTypes.CUSTOM_NAME);
                if (cn instanceof net.kyori.adventure.text.Component) {
                    String t = ((net.kyori.adventure.text.Component) cn).toString();
                    if (t != null && !t.isBlank()) return t.replaceAll("§.", "").trim();
                }
            }
        } catch (Exception e) {}
        return fallback;
    }

    private void closeContainer() {
        try {
            if (containerId >= 0) {
                Bot.INSTANCE.getSession().send(new ServerboundContainerClosePacket(containerId));
                containerId = -1;
            }
        } catch (Exception e) { log.warn("[ChestScanner] 关闭容器失败: {}", e.getMessage()); }
    }

    private void finish() {
        currentTarget = null;
        waitingForContent.set(false);
        if (scanning && !queue.isEmpty()) processNext();
    }

    private void skip() {
        currentTarget = null; waitingForContent.set(false);
        openRetries.clear();
        if (scanning && !queue.isEmpty()) processNext();
    }

    private String itemsToJson(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            Map<String, Object> m = items.get(i);
            sb.append("{\"slot\":").append(m.get("slot"));
            sb.append(",\"id\":").append(m.get("id"));
            sb.append(",\"name\":\"").append(esc(m.get("name"))).append("\"");
            sb.append(",\"count\":").append(m.get("count"));
            if (m.containsKey("displayName") && m.get("displayName") != null) {
                sb.append(",\"displayName\":\"").append(esc(m.get("displayName"))).append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String esc(Object o) {
        if (o == null) return "";
        return o.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static class ScanTarget {
        final int x, y, z;
        ScanTarget(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
