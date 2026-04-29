package com.example.survival;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.packet.Packet;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.plugin.Plugin;
import xin.bbtt.tasks.updateMotionTask;
import xin.bbtt.MovementSync;

import com.example.survival.features.KillAura;
import com.example.survival.features.EndermanManager;
import com.example.survival.features.AutoEat;
import com.example.survival.features.InventoryFeature;
import com.example.survival.features.AntiAFK;
import com.example.survival.features.ActionManager;
import com.example.survival.listeners.PacketListener;
import com.example.survival.utils.ConfigManager;
import com.example.survival.utils.ChestDataManager;
import com.example.survival.utils.ContainerDataManager;
import com.example.survival.features.ChestScanner;

public class SurvivalPlugin implements Plugin {
    @Getter
    private static final Logger logger = LoggerFactory.getLogger("SurvivalManager");
    public static SurvivalPlugin INSTANCE;

    @Getter
    private final ConfigManager configManager = new ConfigManager();

    @Getter
    private final KillAura killAura = new KillAura();
    @Getter
    private final EndermanManager endermanManager = new EndermanManager();
    @Getter
    private final AutoEat autoEat = new AutoEat();
    @Getter
    private final InventoryFeature inventoryManager = new InventoryFeature();
    @Getter
    private final AntiAFK antiAFK = new AntiAFK();

    @Getter
    private final PacketListener packetListener = new PacketListener();

    @Getter
    private final ChestDataManager chestDataManager = new ChestDataManager();

    @Getter
    private final ContainerDataManager containerDataManager = new ContainerDataManager();

    @Getter
    private final ChestScanner chestScanner = new ChestScanner();

    /** 中心化动作管理器 */
    @Getter
    private final ActionManager actionManager = new ActionManager();

    public SurvivalPlugin() {
        INSTANCE = this;
    }

    @Override
    public void onLoad() {
        logger.info("[SurvivalManager] 插件加载中...");
        configManager.init();
        logger.info("[SurvivalManager] 配置文件加载完成");
    }

    @Override
    public void onUnload() {
        logger.info("[SurvivalManager] 插件卸载中...");
        killAura.disable();
        endermanManager.disable();
        autoEat.disable();
        inventoryManager.disable();
        antiAFK.disable();
        chestScanner.disable();
    }

    @Override
    public void onEnable() {
        logger.info("[SurvivalManager] 插件启用中...");
        logger.info("[SurvivalManager] 注册事件监听器...");

        // 注册事件监听器
        Bot.INSTANCE.getPluginManager().events().registerEvents(packetListener, this);

        // ===== NoFall: 默认开启，注册发包拦截强制 onGround=true =====
        Bot.INSTANCE.getSession().addListener(new SessionAdapter() {
            @Override
            public void packetSending(PacketSendingEvent event) {
                Packet packet = event.getPacket();
                String packetName = packet.getClass().getSimpleName();
                if (!packetName.equals("ServerboundMovePlayerPosRotPacket")) return;
                double vy = MovementSync.INSTANCE.velocity.get().y;
                if (vy < configManager.getNoFallVelocityThreshold()) {
                    try {
                        var field = packet.getClass().getField("onGround");
                        field.setBoolean(packet, true);
                    } catch (NoSuchFieldException e) {
                        try {
                            var field = packet.getClass().getSuperclass().getField("onGround");
                            field.setBoolean(packet, true);
                        } catch (Exception ex) {
                            // ignore
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        });
        logger.info("[SurvivalManager] NoFall 无摔落伤害: 默认开启");

        // 根据配置启用功能
        if (configManager.isKillAuraEnabled()) killAura.enable();
        if (configManager.isEndermanManagerEnabled()) endermanManager.enable();
        if (configManager.isAutoEatEnabled()) autoEat.enable();
        if (configManager.isInventoryManagerEnabled()) inventoryManager.enable();
        if (configManager.isAntiAfkEnabled()) antiAFK.enable();

        logger.info("[SurvivalManager] 插件启用完成！");
        logger.info("[SurvivalManager] 杀戮光环: {}", configManager.isKillAuraEnabled() ? "开启" : "关闭");
        logger.info("[SurvivalManager] 末影人管理: {}", configManager.isEndermanManagerEnabled() ? "开启" : "关闭");
        logger.info("[SurvivalManager] 自动进食: {}", configManager.isAutoEatEnabled() ? "开启" : "关闭");
        logger.info("[SurvivalManager] 背包管理: {}", configManager.isInventoryManagerEnabled() ? "开启" : "关闭");
        logger.info("[SurvivalManager] 自动丢弃: {}", configManager.isInventoryAutoDropEnabled() ? "开启" : "关闭");
        logger.info("[SurvivalManager] 防挂机: {}", configManager.isAntiAfkEnabled() ? "开启" : "关闭");
        logger.info("[SurvivalManager] 箱子数据加密存储已就绪");
        logger.info("[SurvivalManager] 动作管理器已就绪");
    }

    @Override
    public void onDisable() {
        logger.info("[SurvivalManager] 插件禁用中...");
        killAura.disable();
        endermanManager.disable();
        autoEat.disable();
        inventoryManager.disable();
        antiAFK.disable();
        chestScanner.disable();
        logger.info("[SurvivalManager] 插件已禁用");
    }
}
