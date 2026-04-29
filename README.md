# SurvivalManager - Xinbot 生存管理插件

基于 MovementSync 的 Xinbot 生存管理插件，提供自动化生存辅助功能。

## 功能特性

| 功能 | 说明 |
|------|------|
| **KillAura** | 自动攻击附近怪物，支持武器优先级、好友豁免 |
| **EndermanManager** | 避免注视末影人，被攻击后自动反击 |
| **AutoEat** | 低血量/饱和度时自动进食，支持金苹果Absorption检测 |
| **InventoryManager** | 自动丢弃垃圾物品，装备耐久度低提醒 |
| **AntiAFK** | 定时执行随机动作避免被服务器踢出 |
| **ChestDataManager** | AES-256加密存储箱子/潜影盒数据 |

## 安装

### 环境要求
- Java 17+
- Maven 3.6+
- Xinbot (含 MovementSync 插件)

### 准备依赖

在构建之前，需要将以下 jar 文件复制到 `lib/` 目录：

```
lib/
├── MovementSync.jar    # MovementSync 插件
├── mcprotocollib.jar   # mcprotocollib 协议库
├── mcbot.jar           # Xinbot/mcbot 核心
└── joml.jar            # JOML 数学库
```

这些文件通常位于 Xinbot 的安装目录中。

### 构建
```bash
# 方式1：使用构建脚本（会自动检查依赖）
build.bat

# 方式2：手动构建
mvn clean package
```

### 安装
将 `target/survival-manager-1.0.0.jar` 复制到 Xinbot 的 `plugins` 目录。

## 配置

### 配置文件位置
- 主配置：`survival/Config/SurvivalManager.yml`
- 好友列表：`survival/Config/Friends.yml`
- 箱子数据：`survival/chest/<dimension>/<hash>.dat`

### 默认配置
```yaml
# KillAura 杀戮光环
killAura:
  enabled: false
  radius: 5.0
  cooldown: 1.0
  maxTargets: 2

# EndermanManager 末影人管理
endermanManager:
  enabled: false
  avoidLookAt: true
  killIfAttacked: true

# AutoEat 自动进食
autoEat:
  enabled: false
  healthThreshold: 18
  saturationThreshold: 18
  respectAbsorption: true

# InventoryManager 背包管理
inventoryManager:
  enabled: false
  durabilityAlertThreshold: 10
  autoDropInterval: 30

# AntiAFK 防挂机
antiAfk:
  enabled: false
  actionInterval: 180
  actions:
    - ROTATE
    - JUMP
    - SWING

# ChestData 箱子加密
chestData:
  enabled: true
  encryptionKey: ""
```

## 命令

所有命令前缀：`survival-management`（别名：`sm`）

### 基础命令
```
sm help          # 显示帮助
sm status        # 查看状态
```

### 功能控制
```
sm killaura [on|off]    # 杀戮光环
sm enderman [on|off]    # 末影人管理
sm autoeat [on|off]     # 自动进食
sm inventory [on|off]   # 背包管理
sm antiafk [on|off]     # 防挂机
```

### 好友管理
```
sm friend add <玩家名>     # 添加好友
sm friend remove <玩家名>  # 移除好友
sm friend list             # 列出好友
```

### 箱子数据管理
```
sm chest save <维度> <x> <y> <z> <数据>   # 保存箱子数据
sm chest load <维度> <x> <y> <z>          # 读取箱子数据
sm chest delete <维度> <x> <y> <z>        # 删除箱子数据
sm chest list <维度>                      # 列出箱子
sm chest stats                            # 统计
```

## 技术架构

```
SurvivalPlugin (主类)
├── features/
│   ├── KillAura          # 杀戮光环
│   ├── EndermanManager   # 末影人管理
│   ├── AutoEat           # 自动进食
│   ├── InventoryManager  # 背包管理
│   └── AntiAFK           # 防挂机
├── commands/
│   └── SurvivalManagementCommandExecutor  # 命令执行器
├── listeners/
│   └── PacketListener    # 数据包监听
└── utils/
    ├── ConfigManager     # 配置管理
    └── ChestDataManager  # 箱子加密存储
```

## 依赖

- MovementSync (通过 JitPack)
- SnakeYAML
- Lombok
- SLF4J

## 许可证

MIT License
