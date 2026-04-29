package com.example.survival.utils;

import com.example.survival.SurvivalPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * 箱子/潜影盒数据加密存储管理器
 *
 * 存储路径: survival/chest/<dimension>/<hash>.dat
 * 加密方式: AES-256
 */
public class ChestDataManager {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    private final Path chestBaseDir;
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    // 加密密钥（从环境变量或配置文件读取，如果没有则使用默认）
    private final byte[] encryptionKey;

    public ChestDataManager() {
        this.chestBaseDir = Paths.get("survival", "chest");

        // 尝试从环境变量读取密钥
        String keyEnv = System.getenv("SURVIVAL_CHEST_KEY");
        if (keyEnv == null || keyEnv.isEmpty()) {
            // 使用默认密钥（基于机器特征生成）
            keyEnv = generateDefaultKey();
        }
        this.encryptionKey = deriveKey(keyEnv);

        initDirectories();
    }

    /**
     * 初始化维度目录
     */
    private void initDirectories() {
        try {
            Files.createDirectories(chestBaseDir.resolve("overworld"));
            Files.createDirectories(chestBaseDir.resolve("nether"));
            Files.createDirectories(chestBaseDir.resolve("the_end"));
            log.info("[ChestData] 箱子数据目录初始化完成");
        } catch (Exception e) {
            log.error("[ChestData] 创建目录失败", e);
        }
    }

    /**
     * 生成默认密钥（基于机器特征）
     */
    private String generateDefaultKey() {
        try {
            // 组合多个机器特征生成唯一密钥
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
            log.warn("[ChestData] 生成默认密钥失败，使用固定密钥");
            return "SurvivalManagerDefaultKey2026";
        }
    }

    /**
     * 从字符串派生 AES 密钥
     */
    private byte[] deriveKey(String keyString) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(keyString.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[ChestData] 密钥派生失败", e);
            // 返回32字节空密钥（会失败但避免崩溃）
            return new byte[32];
        }
    }

    /**
     * 保存箱子数据
     *
     * @param dimension 维度 (overworld/nether/the_end)
     * @param x 箱子X坐标
     * @param y 箱子Y坐标
     * @param z 箱子Z坐标
     * @param data 箱子内容数据（JSON格式或其他序列化数据）
     */
    public void saveChestData(String dimension, int x, int y, int z, String data) {
        try {
            String hash = generateHash(dimension, x, y, z);
            Path filePath = chestBaseDir.resolve(dimension).resolve(hash + ".dat");

            byte[] encrypted = encrypt(data.getBytes(StandardCharsets.UTF_8));
            Files.write(filePath, encrypted);

            log.debug("[ChestData] 保存箱子数据: {} at [{}, {}, {}] -> {}", dimension, x, y, z, hash);
        } catch (Exception e) {
            log.error("[ChestData] 保存箱子数据失败: {} at [{}, {}, {}]", dimension, x, y, z, e);
        }
    }

    /**
     * 读取箱子数据
     *
     * @param dimension 维度
     * @param x X坐标
     * @param y Y坐标
     * @param z Z坐标
     * @return 解密后的数据，如果不存在返回 null
     */
    public String loadChestData(String dimension, int x, int y, int z) {
        try {
            String hash = generateHash(dimension, x, y, z);
            Path filePath = chestBaseDir.resolve(dimension).resolve(hash + ".dat");

            if (!Files.exists(filePath)) {
                return null;
            }

            byte[] encrypted = Files.readAllBytes(filePath);
            byte[] decrypted = decrypt(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[ChestData] 读取箱子数据失败: {} at [{}, {}, {}]", dimension, x, y, z, e);
            return null;
        }
    }

    /**
     * 删除箱子数据
     */
    public boolean deleteChestData(String dimension, int x, int y, int z) {
        try {
            String hash = generateHash(dimension, x, y, z);
            Path filePath = chestBaseDir.resolve(dimension).resolve(hash + ".dat");
            return Files.deleteIfExists(filePath);
        } catch (Exception e) {
            log.error("[ChestData] 删除箱子数据失败", e);
            return false;
        }
    }

    /**
     * 列出指定维度的所有箱子数据
     */
    public List<ChestEntry> listChests(String dimension) {
        List<ChestEntry> entries = new ArrayList<>();
        try {
            Path dimPath = chestBaseDir.resolve(dimension);
            if (!Files.exists(dimPath)) return entries;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dimPath, "*.dat")) {
                for (Path file : stream) {
                    String filename = file.getFileName().toString();
                    String hash = filename.substring(0, filename.length() - 4);

                    try {
                        byte[] encrypted = Files.readAllBytes(file);
                        byte[] decrypted = decrypt(encrypted);
                        String data = new String(decrypted, StandardCharsets.UTF_8);

                        entries.add(new ChestEntry(hash, dimension, data, Files.size(file)));
                    } catch (Exception e) {
                        log.debug("[ChestData] 跳过损坏的文件: {}", filename);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ChestData] 列出箱子数据失败", e);
        }
        return entries;
    }

    /**
     * 获取所有维度的箱子统计
     */
    public Map<String, Integer> getChestStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        String[] dimensions = {"overworld", "nether", "the_end"};
        for (String dim : dimensions) {
            stats.put(dim, listChests(dim).size());
        }
        return stats;
    }

    // ====== 加密/解密 ======

    private byte[] encrypt(byte[] data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        return cipher.doFinal(data);
    }

    private byte[] decrypt(byte[] data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        return cipher.doFinal(data);
    }

    /**
     * 生成位置哈希
     */
    private String generateHash(String dimension, int x, int y, int z) {
        String input = String.format("%s:%d:%d:%d", dimension, x, y, z);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            // 回退到简单哈希
            return String.format("%08x", input.hashCode());
        }
    }

    // ====== 数据类 ======

    public record ChestEntry(String hash, String dimension, String data, long size) {}
}
