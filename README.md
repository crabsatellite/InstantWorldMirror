# InstantWorldMirror - 瞬时世界之镜

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21-green.svg)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.0+-orange.svg)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

一个Minecraft模组，允许玩家使用"次元镜像镜"创建当前世界的镜像副本并探索其中。

## ✨ 特性

### 🪞 次元镜像镜

- 使用EPIC稀有度的特殊物品
- 右键固体方块创建传送门
- 自带附魔光效

### 🌍 镜面世界

- 实时复制主世界地形（默认10区块半径）
- 完全独立的维度，不影响主世界
- 同步主世界天气和时间
- 禁止生成任何生物

### 🚪 传送门系统

- 临时传送门实体（持续5秒）
- 仅限创建者进入
- 浮动动画和粒子效果
- 自动音效提示

### 🎲 盗梦空间彩蛋

- 1/100概率触发
- 玩家出生在基岩层上方
- 上方是完全倒置的世界
- 致敬电影《盗梦空间》

## 📦 安装

1. 安装 [NeoForge](https://neoforged.net/) 1.21+
2. 下载本模组 JAR 文件
3. 放入 `.minecraft/mods` 文件夹
4. 启动游戏

## 🔧 合成配方

```
[玻璃] [黑曜石] [玻璃]
[黑曜石] [末影珍珠] [黑曜石]
[玻璃] [黑曜石] [玻璃]
```

## 📋 命令

| 命令                                       | 描述                 | 权限     |
| ------------------------------------------ | -------------------- | -------- |
| `/mirror return`                           | 强制返回主世界       | 所有玩家 |
| `/mirror mob on/off`                       | 开关镜面世界生物生成 | OP       |
| `/mirror admin <玩家>`                     | 赋予管理员权限       | OP       |
| `/mirror allow <玩家>`                     | 允许玩家进入镜面世界 | OP       |
| `/mirror deny <玩家>`                      | 禁止玩家进入镜面世界 | OP       |
| `/mirror itemtransfer <玩家> <true/false>` | 控制物品带回权限     | OP       |

## ⚙️ 配置

配置文件位于 `config/instantworldmirror-common.toml`

| 配置项            | 默认值 | 描述                    |
| ----------------- | ------ | ----------------------- |
| copyChunkRadius   | 10     | 世界复制半径（区块）    |
| mirrorEnabled     | true   | 镜像功能开关            |
| portalDuration    | 5      | 传送门持续时间（秒）    |
| allowItemTransfer | false  | 默认是否允许带回物品    |
| enableMobSpawning | false  | 镜面世界生物生成        |
| inceptionMode     | false  | 强制开启盗梦空间模式    |
| inceptionChance   | 100    | 盗梦空间触发概率（1/N） |

## 🎮 游戏玩法

1. **合成次元镜像镜**
2. **找到你想复制的区域**
3. **右键固体方块**创建传送门
4. **进入传送门**进入镜面世界
5. 在镜面世界中自由探索、建造、破坏
6. **再次使用镜像镜**或使用 `/mirror return` 返回
7. 死亡会自动返回主世界（物品留在镜面世界）

## ⚠️ 注意事项

- 默认情况下，从镜面世界返回时**不会保留物品**
- 镜面世界中**无法建造下界/末地传送门**
- 每次进入都会**重新复制世界**
- 服务器管理员可以控制玩家访问权限

## 🔗 相关链接

- [NeoForge 文档](https://docs.neoforged.net/)
- [NeoForge Discord](https://discord.neoforged.net/)

## 📄 许可证

MIT License
