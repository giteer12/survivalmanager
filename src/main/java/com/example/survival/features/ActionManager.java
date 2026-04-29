package com.example.survival.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 动作管理器 - 中心化动作调度，协调 AutoEat / EndermanManager / KillAura 等功能的冲突
 * 
 * 优先级（数值越大优先级越高）：
 *   EAT(10) - 吃东西
 *   COMBAT(5) - 战斗
 *   MOVEMENT(1) - 移动/转向
 */
public class ActionManager {
    private static final Logger log = LoggerFactory.getLogger("SurvivalManager");

    public enum ActionPriority {
        LOW(1),
        COMBAT(5),
        HIGH(8),
        CRITICAL(10);

        public final int value;
        ActionPriority(int value) { this.value = value; }
    }

    public enum ActionType {
        TOTEM(15),  // 副手图腾补充（最高优先级）
        EAT(10),
        ATTACK(5),
        LOOK(1),
        SCANNER(1);

        public final int basePriority;
        ActionType(int basePriority) { this.basePriority = basePriority; }
    }

    /** 当前持有动作锁的功能 */
    private final AtomicReference<ActionHolder> currentAction = new AtomicReference<>(null);
    
    /** 锁超时释放（5秒超时） */
    private static final long ACTION_TIMEOUT_MS = 5000;

    /** 由特定动作类型检查是否可以获取 */
    public boolean tryAcquire(ActionType type) {
        ActionHolder current = currentAction.get();
        
        // 检查超时：锁持有超过5秒自动释放
        if (current != null) {
            long holdTime = System.currentTimeMillis() - current.acquireTime;
            if (holdTime > ACTION_TIMEOUT_MS) {
                log.warn("[ActionManager] 动作 {} 超时({}ms)，强制释放", current.type, holdTime);
                currentAction.set(null);
                current = null;
            } else {
                log.debug("[ActionManager] 当前持有锁: {} (优先级{}, 线程{}, 已持有{}ms)", 
                    current.type, current.priority, current.owner.getName(), holdTime);
            }
        }
        
        if (current == null) {
            ActionHolder holder = new ActionHolder(type, type.basePriority, Thread.currentThread(), System.currentTimeMillis());
            return currentAction.compareAndSet(null, holder);
        }
        // 相同类型：允许（已经是这个类型在执行）
        if (current.type == type) return true;
        // 同线程：允许（同一个功能内部调用）
        if (current.owner == Thread.currentThread()) return true;
        // 新动作优先级 > 当前：打断
        if (type.basePriority > current.priority) {
            ActionHolder newHolder = new ActionHolder(type, type.basePriority, Thread.currentThread(), System.currentTimeMillis());
            return currentAction.compareAndSet(current, newHolder);
        }
        return false;
    }

    /** 尝试获取指定优先级以上的执行权限（用于检查是否能执行） */
    public boolean canExecute(ActionType type) {
        ActionHolder current = currentAction.get();
        if (current == null) return true;
        if (current.type == type) return true;
        if (current.owner == Thread.currentThread()) return true;
        return type.basePriority > current.priority;
    }

    /** 释放动作锁 */
    public void release(ActionType type) {
        ActionHolder current = currentAction.get();
        if (current != null && current.type == type) {
            currentAction.compareAndSet(current, null);
        }
    }

    /** 强制释放（仅当持有者确实结束时调用） */
    public void forceRelease() {
        currentAction.set(null);
    }

    /** 检查是否处于忙碌状态（高优动作正在执行） */
    public boolean isBusy() {
        ActionHolder current = currentAction.get();
        return current != null && current.priority >= ActionPriority.COMBAT.value;
    }

    public String getStatus() {
        ActionHolder h = currentAction.get();
        if (h == null) return "空闲";
        return h.type.name() + "(优先级=" + h.priority + ")";
    }

    private record ActionHolder(ActionType type, int priority, Thread owner, long acquireTime) {}
}
