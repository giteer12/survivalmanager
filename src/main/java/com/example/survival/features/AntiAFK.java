package com.example.survival.features;

import com.example.survival.SurvivalPlugin;
import com.example.survival.utils.ConfigManager;
import xin.bbtt.MovementSync;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import xin.bbtt.movements.JumpMovement;
// 移除了未使用的导入: ActionMovement
import xin.bbtt.movements.LookAtMovement;
import xin.bbtt.movements.WalkMovement;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 防挂机 - 定时执行随机动作避免被踢出
 *
 * 动作类型：
 * - ROTATE: 随机转头
 * - JUMP: 跳跃
 * - WALK: 小步移动
 * - SWING: 挥手
 */
public class AntiAFK {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    private volatile boolean enabled = false;
    private ScheduledExecutorService scheduler;
    private Random random = new Random();

    /** 上次动作时间 */
    private volatile long lastActionTime = 0;
    /** 当前正在执行的动作 */
    private volatile ActionType currentAction = null;
    /** 动作开始时间 */
    private volatile long actionStartTime = 0;

    public enum ActionType {
        ROTATE, JUMP, WALK, SWING
    }

    public void enable() {
        if (enabled) return;
        enabled = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AntiAFK-Tick");
            t.setDaemon(true);
            return t;
        });
        // 每10秒检查一次是否需要动作
        scheduler.scheduleAtFixedRate(this::tick, 5000, 10000, TimeUnit.MILLISECONDS);
        log.info("[AntiAFK] 已启用");
    }

    public void disable() {
        enabled = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        currentAction = null;
        log.info("[AntiAFK] 已禁用");
    }

    public boolean isEnabled() { return enabled; }

    private void tick() {
        if (!enabled) return;

        ConfigManager cfg = SurvivalPlugin.INSTANCE.getConfigManager();
        int actionInterval = cfg.getAntiAfkActionInterval(); // 秒
        List<String> actionStrings = cfg.getAntiAfkActions();
        int rotateRange = cfg.getAntiAfkRotateRange();

        long now = System.currentTimeMillis();

        // 检查当前动作是否完成
        if (currentAction != null) {
            long actionDuration = getActionDuration(currentAction);
            if (now - actionStartTime >= actionDuration) {
                // 动作完成，重置状态
                finishAction(currentAction);
                currentAction = null;
                lastActionTime = now;
                log.debug("[AntiAFK] 动作完成");
            }
            return; // 正在执行动作，不开始新动作
        }

        // 检查是否需要执行动作
        long intervalMs = actionInterval * 1000L;
        if (now - lastActionTime < intervalMs) return;

        // 解析可用动作
        List<ActionType> availableActions = new ArrayList<>();
        for (String actionStr : actionStrings) {
            try {
                availableActions.add(ActionType.valueOf(actionStr.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        if (availableActions.isEmpty()) {
            availableActions = List.of(ActionType.ROTATE, ActionType.SWING);
        }

        // 随机选择一个动作
        ActionType selectedAction = availableActions.get(random.nextInt(availableActions.size()));
        executeAction(selectedAction, rotateRange);
    }

    /**
     * 执行指定动作
     */
    private void executeAction(ActionType action, int rotateRange) {
        currentAction = action;
        actionStartTime = System.currentTimeMillis();

        switch (action) {
            case ROTATE -> executeRotate(rotateRange);
            case JUMP -> executeJump();
            case WALK -> executeWalk();
            case SWING -> executeSwing();
        }

        log.debug("[AntiAFK] 执行动作: {}", action);
    }

    /**
     * 完成动作后的清理
     */
    private void finishAction(ActionType action) {
        // WalkMovement 和 JumpMovement 会自动处理停止逻辑
        // 不需要手动发送位置包
    }

    /**
     * 随机转头 - 使用 MovementSync 的 LookAtMovement
     */
    private void executeRotate(int rotateRange) {
        float currentYaw = MovementSync.INSTANCE.yaw.get();
        float currentPitch = MovementSync.INSTANCE.pitch.get();

        // 随机偏移
        float yawOffset = (random.nextFloat() - 0.5f) * 2 * rotateRange;
        float pitchOffset = (random.nextFloat() - 0.5f) * 2 * (rotateRange / 2f);

        float newYaw = normalizeYaw(currentYaw + yawOffset);
        float newPitch = clampPitch(currentPitch + pitchOffset);

        // 计算目标位置（基于当前位置 + 旋转角度）
        Vector3d headPos = MovementSync.INSTANCE.getHeadPosition();
        double yawRad = Math.toRadians(newYaw);
        double pitchRad = Math.toRadians(newPitch);
        double distance = 5.0; // 看向5格远的位置

        double targetX = headPos.x - Math.sin(yawRad) * Math.cos(pitchRad) * distance;
        double targetY = headPos.y - Math.sin(pitchRad) * distance;
        double targetZ = headPos.z + Math.cos(yawRad) * Math.cos(pitchRad) * distance;

        Vector3d lookTarget = new Vector3d(targetX, targetY, targetZ);

        // 使用 MovementSync 的 LookAtMovement 平滑转头
        MovementSync.INSTANCE.getMovementController().addMovement(new LookAtMovement(lookTarget));
    }

    /**
     * 跳跃 - 使用 MovementSync 的 JumpMovement
     */
    private void executeJump() {
        // 使用 MovementSync 内置的跳跃功能
        MovementSync.INSTANCE.getMovementController().addMovement(new JumpMovement());
    }

    /**
     * 小步移动 - 使用 MovementSync 的 WalkMovement
     */
    private void executeWalk() {
        float yaw = MovementSync.INSTANCE.yaw.get();

        // 随机方向移动
        double distance = 0.1 + random.nextDouble() * 0.15; // 较小的速度
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad) * distance;
        double dz = Math.cos(yawRad) * distance;

        // 使用 WalkMovement 给玩家一个速度向量，持续 500ms
        Vector3d velocity = new Vector3d(dx, 0, dz);
        MovementSync.INSTANCE.getMovementController().addMovement(new WalkMovement(velocity, 500));
    }

    /**
     * 挥手
     */
    private void executeSwing() {
        try {
            var session = xin.bbtt.mcbot.Bot.INSTANCE.getSession();
            if (session != null) {
                session.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
            }
        } catch (Exception e) {
            log.debug("[AntiAFK] 挥手异常: {}", e.getMessage());
        }
    }

    /**
     * 获取动作持续时间
     */
    private long getActionDuration(ActionType action) {
        return switch (action) {
            case ROTATE -> 500;   // 转头很快
            case JUMP -> 800;     // 跳跃约 0.8 秒
            case WALK -> 1000;    // 移动约 1 秒
            case SWING -> 300;    // 挥手很快
        };
    }

    // ====== 辅助方法 ======

    private float normalizeYaw(float yaw) {
        float normalized = yaw % 360;
        if (normalized > 180) normalized -= 360;
        if (normalized < -180) normalized += 360;
        return normalized;
    }

    private float clampPitch(float pitch) {
        return Math.max(-90, Math.min(90, pitch));
    }


}
