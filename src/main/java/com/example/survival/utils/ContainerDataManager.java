package com.example.survival.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 容器数据管理器
 *
 * 目录结构：
 * survival/container/chest/<dim>/<hash>.dat    ← 箱子数据（AES加密）
 * survival/container/shulker/<dim>/<hash>.txt   ← 潜影盒记录（明文）
 */
public class ContainerDataManager {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    private final Path chestBaseDir;
    private final Path shulkerBaseDir;
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private final byte[] encryptionKey;

    public ContainerDataManager() {
        Path containerDir = Paths.get("survival", "container");
        this.chestBaseDir = containerDir.resolve("chest");
        this.shulkerBaseDir = containerDir.resolve("shulker");

        String keyEnv = System.getenv("SURVIVAL_CHEST_KEY");
        if (keyEnv == null || keyEnv.isEmpty()) {
            keyEnv = generateDefaultKey();
        }
        this.encryptionKey = deriveKey(keyEnv);
        initDirectories();
    }

    private void initDirectories() {
        try {
            for (String dim : new String[]{"overworld", "nether", "the_end"}) {
                Files.createDirectories(chestBaseDir.resolve(dim));
                Files.createDirectories(shulkerBaseDir.resolve(dim));
            }
            log.info("[ContainerData] 目录: chest={}", chestBaseDir.toAbsolutePath());
            log.info("[ContainerData] 目录: shulker={}", shulkerBaseDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("[ContainerData] 创建目录失败", e);
        }
    }

    private String generateDefaultKey() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(System.getProperty("user.name", "user"));
            sb.append(System.getProperty("os.name", "os"));
            sb.append(System.getProperty("os.version", "1.0"));
            sb.append(System.getenv("COMPUTERNAME"));
            sb.append(System.getenv("USERDOMAIN"));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "SurvivalManagerDefaultKey2026";
        }
    }

    private byte[] deriveKey(String keyString) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(keyString.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[32];
        }
    }

    /**
     * 保存箱子数据（加密）
     * itemsJson 格式: [{"slot":n,"id":n,"name":"...","count":n},...]
     */
    public void saveChestData(String dimension, int x, int y, int z,
                              String itemsJson, int containerType, boolean isEmpty) {
        try {
            String hash = generateHash(dimension, x, y, z);
            Path filePath = chestBaseDir.resolve(dimension).resolve(hash + ".dat");
            String fullData = "{\"dimension\":\"" + escJson(dimension) + "\",\"x\":" + x
                    + ",\"y\":" + y + ",\"z\":" + z
                    + ",\"type\":" + containerType + ",\"empty\":" + isEmpty
                    + ",\"items\":" + itemsJson + "}";
            byte[] encrypted = encrypt(fullData.getBytes(StandardCharsets.UTF_8));
            Files.write(filePath, encrypted);
            log.info("[ContainerData] 保存箱子: [{}] at [{},{},{}] empty={}", dimension, x, y, z, isEmpty);
        } catch (Exception e) {
            log.error("[ContainerData] 保存箱子失败", e);
        }
    }

    public String loadChestData(String dimension, int x, int y, int z) {
        try {
            String hash = generateHash(dimension, x, y, z);
            Path filePath = chestBaseDir.resolve(dimension).resolve(hash + ".dat");
            if (!Files.exists(filePath)) return null;
            byte[] decrypted = decrypt(Files.readAllBytes(filePath));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存潜影盒记录（明文，追加模式）
     * 格式: slot=N,pos=[x,y,z],name=显示名,type=物品类型名,count=N
     */
    public void saveShulkerRecord(String dimension, int containerX, int containerY, int containerZ,
                                   int slot, String displayName, String typeName, int count) {
        try {
            String parentHash = generateHash(dimension, containerX, containerY, containerZ);
            Path filePath = shulkerBaseDir.resolve(dimension).resolve(parentHash + ".txt");
            String line = String.format("slot=%d,pos=[%d,%d,%d],name=%s,type=%s,count=%d%n",
                    slot, containerX, containerY, containerZ, esc(displayName), esc(typeName), count);
            Files.write(filePath, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("[ContainerData] 潜影盒: {} x{} > {}", displayName, count, parentHash);
        } catch (Exception e) {
            log.warn("[ContainerData] 保存潜影盒失败", e);
        }
    }

    public List<String> loadShulkerRecords(String dimension, int x, int y, int z) {
        try {
            String hash = generateHash(dimension, x, y, z);
            Path filePath = shulkerBaseDir.resolve(dimension).resolve(hash + ".txt");
            if (!Files.exists(filePath)) return Collections.emptyList();
            return Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Map<String, Integer> getChestStats() {
        Map<String, Integer> s = new LinkedHashMap<>();
        for (String d : new String[]{"overworld", "nether", "the_end"}) s.put(d, countFiles(chestBaseDir.resolve(d)));
        return s;
    }

    public Map<String, Integer> getShulkerStats() {
        Map<String, Integer> s = new LinkedHashMap<>();
        for (String d : new String[]{"overworld", "nether", "the_end"}) s.put(d, countFiles(shulkerBaseDir.resolve(d)));
        return s;
    }

    public int getChestCount() {
        return countFiles(chestBaseDir.resolve("overworld"))
             + countFiles(chestBaseDir.resolve("nether"))
             + countFiles(chestBaseDir.resolve("the_end"));
    }

    public int getShulkerCount() {
        return countFiles(shulkerBaseDir.resolve("overworld"))
             + countFiles(shulkerBaseDir.resolve("nether"))
             + countFiles(shulkerBaseDir.resolve("the_end"));
    }

    private int countFiles(Path dir) {
        int c = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) { for (Path ignored : s) c++; } catch (Exception e) {}
        return c;
    }

    public List<ContainerEntry> listChests(String dimension) {
        List<ContainerEntry> entries = new ArrayList<>();
        try {
            Path dimPath = chestBaseDir.resolve(dimension);
            if (!Files.exists(dimPath)) return entries;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dimPath)) {
                for (Path file : stream) {
                    String fn = file.getFileName().toString();
                    String hash = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;
                    long fileSize = Files.size(file);
                    // 尝试从文件中读取坐标
                    try {
                        byte[] decrypted = decrypt(Files.readAllBytes(file));
                        String content = new String(decrypted, StandardCharsets.UTF_8);
                        int xi = 0, yi = 0, zi = 0;
                        int type = 0;
                        boolean empty = false;
                        Matcher m = COORD_PATTERN.matcher(content);
                        if (m.find()) {
                            xi = Integer.parseInt(m.group(1));
                            yi = Integer.parseInt(m.group(2));
                            zi = Integer.parseInt(m.group(3));
                        }
                        Matcher tm = TYPE_PATTERN.matcher(content);
                        if (tm.find()) type = Integer.parseInt(tm.group(1));
                        Matcher em = EMPTY_PATTERN.matcher(content);
                        if (em.find()) empty = Boolean.parseBoolean(em.group(1));
                        entries.add(new ContainerEntry(hash, dimension, xi, yi, zi, type, empty, fileSize));
                    } catch (Exception e) {
                        // 解密失败（密钥变更等原因），降级显示 hash-only
                        entries.add(new ContainerEntry(hash, dimension, 0, 0, 0, 0, false, fileSize));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ContainerData] 列出箱子失败", e);
        }
        return entries;
    }

    // ====== 加密/解密 ======
    private byte[] encrypt(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, ALGORITHM));
        return c.doFinal(data);
    }

    private byte[] decrypt(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, ALGORITHM));
        return c.doFinal(data);
    }

    private static final Pattern COORD_PATTERN = Pattern.compile("\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+)");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\"type\":(-?\\d+)");
    private static final Pattern EMPTY_PATTERN = Pattern.compile("\"empty\":(true|false)");

    /**
     * JSON字符串转义（仅转义引号和反斜杠）
     */
    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String generateHash(String dimension, int x, int y, int z) {
        String input = String.format("%s:%d:%d:%d", dimension, x, y, z);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.format("%08x", input.hashCode());
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=");
    }

    public record ContainerEntry(String hash, String dimension, int x, int y, int z, int type, boolean empty, long size) {}
}
