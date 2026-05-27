# SurvivalManager

基于 [xinbot](https://github.com/xinbots/xinbot) 2.0.1 + [MovementSync](https://github.com/xinbots/MovementSync) 的生存自动化插件，支持自动攻击、末影人管理、自动进食、背包管理、防挂机等功能的 Minecraft 机器人管理插件。

**Java 版本**: 17  
**前置依赖**: xinbot 2.0.1, MovementSync 1.5.0

---

## 功能模块

### 1. KillAura（杀戮光环）
自动攻击范围内的怪物或玩家，优先使用快捷栏最佳武器。

**配置项**:
- `killAura.enabled` - 开关
- `killAura.radius` - 检测半径 (默认 5.0)
- `killAura.cooldown` - 攻击冷却 (秒，默认 1.0)
- `killAura.maxTargets` - 最大同时攻击目标数 (默认 2)
- `killAura.attackPlayers` - 是否攻击玩家 (默认 false)
- `killAura.weapons` - 武器优先级列表
- `killAura.monsters` - 怪物类型列表

**命令**: `/sm aura [on|off]`

**特性**:
- 按距离排序目标
- 支持好友白名单（不被攻击）
- 武器自动切换（优先快捷栏，回退背包）
- 范围内目标日志输出，[警报] 标记近距离目标

---

### 2. EndermanManager（末影人管理）
处理末影人的三种策略：避免看向、主动看向、反击。

**配置项**:
- `endermanManager.enabled` - 开关
- `endermanManager.avoidLookAt` - 避免看向末影人
- `endermanManager.lookAt` - 主动看向末影人
- `endermanManager.attackRange` - 攻击范围

**命令**: `/sm enderman [on|off|avoid|look|attack]`

**特性**:
- 支持被攻击时自动反击（Retaliate 子模块）
- 与 KillAura 协同工作

---

### 3. AutoEat（自动进食）
自动检测饥饿值/饱和度并进食，危急时刻优先金苹果。

**配置项**:
- `autoEat.enabled` - 开关
- `autoEat.healthThreshold` - 生命值阈值 (默认 18)
- `autoEat.saturationThreshold` - 饱和度阈值 (默认 18)
- `autoEat.respectAbsorption` - 有 Absorption 时跳过金苹果
- `autoEat.priority` - 食物优先级列表
- `autoEat.blacklist` - 食物黑名单

**命令**: `/sm eat [on|off]`

**特性**:
- 1.8 秒进食动画（1800ms），期间锁定动作
- 危急时刻（<18 生命）优先金苹果
- 通过 ActionManager 获取执行权限（可打断战斗/移动）
- 冷却时间 2 秒

---

### 4. InventoryFeature（背包管理）

#### 4.1 自动丢弃
- `inventoryManager.autoDrop.enabled` - 开关
- `inventoryManager.autoDrop` - 自动丢弃物品列表
- `inventoryManager.autoDropInterval` - 检查间隔（秒）

#### 4.2 自动补充（快捷栏）
- `inventoryManager.autoRefill.enabled` - 开关
- `inventoryManager.autoRefill.threshold` - 触发阈值（低于此数量补充）
- `inventoryManager.autoRefill.items` - 监控物品列表
- `inventoryManager.autoRefill.alertItems` - 提醒物品列表（背包为空时）

#### 4.3 经验修补
- `inventoryManager.mending.enabled` - 开关
- `inventoryManager.mending.durabilityThreshold` - 触发耐久度阈值

#### 4.4 自动穿戴更好装备
- `inventoryManager.betterArmorAutoEquip.enabled` - 开关
- `inventoryManager.betterArmorAlert.enabled` - 装备警报开关

#### 4.5 图腾管理
- `inventoryManager.totem.dedicatedSlot` - 图腾专用槽位 (0-8)

#### 4.6 装备管理
- `inventoryManager.equipment.dedicatedSlot` - 装备存放专用槽位 (0-8)
- `inventoryManager.mending.dedicatedSlot` - 经验修补专用槽位 (0-8)

#### 4.7 耐久度警报
- `inventoryManager.durabilityAlertThreshold` - 耐久度警报阈值

**命令**: `/sm inv [on|off|refill|mending|hotbar]`

---

### 5. AntiAFK（防挂机）
定时执行随机动作避免被服务器踢出。

**配置项**:
- `antiAfk.enabled` - 开关
- `antiAfk.actionInterval` - 动作间隔（秒，默认 180）
- `antiAfk.actions` - 动作列表 `[ROTATE, JUMP, WALK, SWING]`
- `antiAfk.rotateRange` - 转头角度范围（默认 30°）

**命令**: `/sm afk [on|off]`

**动作类型**:
- `ROTATE` - 随机转头（使用 LookAtMovement 平滑旋转）
- `JUMP` - 跳跃（JumpMovement）
- `WALK` - 小步移动（WalkMovement）
- `SWING` - 挥手（挥手数据包）

---

### 6. ChestScanner（箱子扫描器）（已停止维护）
扫描指定范围内的所有容器方块，记录坐标和内容。

**配置项**:
- `chestScanner.enabled` - 开关
- `chestScanner.scanRange` - 扫描半径（默认 32）
- `chestScanner.scanHeight.min` - 最低 Y 轴
- `chestScanner.scanHeight.max` - 最高 Y 轴
- `chestScanner.scanHeight.discrete` - 离散高度层模式
- `chestScanner.scanHeight.layers` - 离散高度列表

**命令**: `/sm scan [range|height]`

**特性**:
- 支持连续高度范围和离散高度层
- 扫描结果加密存储（ChestDataManager）

---

### 7. NoFall（无摔落伤害）
默认开启，防止摔落伤害。

**配置项**:
- `noFall.velocityThreshold` - 速度阈值（默认 -0.5）

---

### 8. 好友系统
独立配置文件 `Friends.yml`，支持添加/删除好友。

**命令**: `/sm friend add <name>`, `/sm friend remove <name>`, `/sm friend list`

---

## 架构设计

### 动作协调（ActionManager）
中心化动作调度器，解决多功能间的冲突。

**优先级**（数值越大优先级越高）:
- `TOTEM(15)` - 副手图腾（最高）
- `EAT(10)` - 进食
- `ATTACK(5)` - 战斗
- `LOOK(1)` - 看向
- `SCANNER(1)` - 扫描

**超时机制**: 动作锁持有超过 5 秒自动释放。

### 数据包监听（PacketListener）
统一处理服务器数据包，提供实时数据：
- 生命值 / 饥饿值 / 饱和度 / Absorption
- 容器状态同步（stateId / containerId）
- 物品变化监听（图腾消耗、快捷栏变动）
- 实体属性更新

### 配置管理（ConfigManager）
YAML 配置读写，支持嵌套路径：
```java
config.getNested("killAura.radius")   // 读取
setNested("killAura.enabled", true)   // 写入并自动保存
```

---

## 命令列表

| 命令 | 别名 | 功能 |
|------|------|------|
| `/survival-management` | `/sm` | 主命令 |
| `/sm aura [on/off]` | | 杀戮光环开关 |
| `/sm enderman [on/off/avoid/look/attack]` | | 末影人管理 |
| `/sm eat [on/off]` | | 自动进食开关 |
| `/sm inv [on/off/refill/mending/hotbar]` | | 背包管理 |
| `/sm afk [on/off]` | | 防挂机开关 |
| `/sm scan [range/height]` | | 箱子扫描 |
| `/sm friend [add/remove/list]` | | 好友管理 |
| `/sm view` | | 背包查看 |

---

## 文件结构

```
src/main/java/com/example/survival/
├── SurvivalPlugin.java                    # 主插件入口
├── commands/
│   └── SurvivalManagementCommandExecutor.java  # 命令执行器
├── features/
│   ├── KillAura.java                    # 杀戮光环
│   ├── EndermanManager.java              # 末影人管理
│   ├── AutoEat.java                      # 自动进食
│   ├── InventoryFeature.java             # 背包管理
│   ├── ActionManager.java                 # 动作协调
│   ├── AntiAFK.java                      # 防挂机
│   └── ChestScanner.java                  # 箱子扫描
├── listeners/
│   └── PacketListener.java               # 数据包监听
├── utils/
│   ├── ConfigManager.java                 # 配置管理
│   ├── ChestDataManager.java              # 箱子数据管理
│   └── ContainerDataManager.java         # 容器数据管理
└── utils/ItemTranslator.java             # 物品翻译
```

**配置文件**:
- `survival/Config/SurvivalManager.yml` - 主配置
- `survival/Config/Friends.yml` - 好友列表

---

## 依赖

```xml
<dependencies>
    <!-- xinbot -->
    <dependency>
        <groupId>xin.bbtt</groupId>
        <artifactId>xinbot</artifactId>
        <version>2.0.1</version>
    </dependency>
    
    <!-- MovementSync -->
    <dependency>
        <groupId>xin.bbtt</groupId>
        <artifactId>MovementSync</artifactId>
        <version>1.5.0</version>
    </dependency>
    
    <!-- JOML -->
    <dependency>
        <groupId>org.joml</groupId>
        <artifactId>joml</artifactId>
        <version>1.10.5</version>
    </dependency>
    
    <!-- SnakeYAML -->
    <dependency>
        <groupId>org.yaml</groupId>
        <artifactId>snakeyaml</artifactId>
        <version>2.2</version>
    </dependency>
    
    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.30</version>
    </dependency>
</dependencies>
```

---

## 构建

```bash
mvn clean package
```

产物: `target/SurvivalManager-{version}.jar`
