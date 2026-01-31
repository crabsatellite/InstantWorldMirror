# InstantWorldMirror - Instant World Mirror

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21-green.svg)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.0+-orange.svg)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**English** | **[中文](https://github.com/crabsatellite/InstantWorldMirror/blob/1.21.1/docs/readme/chn/README.md)**

## Instant Sandbox - Explore Without Consequences

InstantWorldMirror provides Minecraft players with an instant sandbox testing ground. Using the Dimension Mirror, you can create a complete mirror copy of the current world at any location, freely explore, test, and experiment without worrying about any impact on the original world.

### Core Use Cases

- **Mod Testing**: Test new mod functionality and compatibility in the mirror world without risking your main save
- **Redstone Debugging**: Copy your redstone contraptions for repeated testing and debugging without teardown costs
- **Adventure Scouting**: Explore dangerous areas in the mirror first to plan safe routes before venturing in
- **Building Design**: Preview building plans in the mirror, then build in the main world once satisfied
- **Server Events**: Provide safe activity spaces for players, with automatic cleanup after events

## Features

### Dimension Mirror

The Dimension Mirror is your key to the mirror world:

- Right-click any solid block to create an entry portal
- Supports Efficiency enchantment to reduce cooldown (20% reduction per level)
- No cooldown in Creative mode
- Supports creating mirrors from Overworld, Nether, End, and mod-added custom dimensions

### Mirror World Mechanics

- Copies terrain within a configurable radius centered on the portal (default 10 chunks, configurable 1-32 chunks)
- Complete copying of blocks, block entities, biome data, and structure information
- Optional entity copying (mobs, decorations, etc.)
- Automatic synchronization of source world weather and time
- Natural mob spawning disabled by default, can be enabled via commands or config

### Multiplayer Support

- Supports up to 8 parallel mirror dimensions, allowing multiple players to use their own mirror worlds simultaneously
- Multiple players can enter the same mirror session through the same portal
- Comprehensive permission system for server admins to control player access and item transfer rules
- Mirror world automatically enters cleanup when all players leave

### Portal System

- Entry portal opens after world copy completes, lasts 5 minutes by default (configurable)
- Return portal in mirror world is permanent by default
- Use Dimension Mirror in mirror world to create return portal
- Automatic return to source world upon death

### Performance Optimization

- Heightmap scanning technology skips air columns, significantly reducing unnecessary copying
- Section-level operations instead of block-by-block processing
- Sequential queue processing to avoid server overload
- Smart cleanup mechanism only processes modified chunks

## Installation

1. Install [NeoForge](https://neoforged.net/) 1.21+
2. Download this mod JAR file
3. Place in `.minecraft/mods` folder
4. Launch the game

## Crafting Recipe

```
[Glass]    [Obsidian] [Glass]
[Obsidian] [Ender Pearl] [Obsidian]
[Glass]    [Obsidian] [Glass]
```

## Commands

All commands start with `/iwm`:

| Command                                   | Description                                                 | Permission   |
| ----------------------------------------- | ----------------------------------------------------------- | ------------ |
| `/iwm return`                             | Force return to source world                                | All players  |
| `/iwm mob on/off/status`                  | Control mirror world mob spawning                           | OP           |
| `/iwm allow <player>`                     | Allow player to use mirror feature                          | OP           |
| `/iwm deny <player>`                      | Deny player from using mirror feature                       | OP           |
| `/iwm itemtransfer <player> <true/false>` | Control player item transfer permission                     | OP           |
| `/iwm status`                             | View dimension pool status and player info                  | OP           |
| `/iwm forceclear <dimension>`             | Force clear specified mirror dimension                      | OP (Level 3) |
| `/iwm purge`                              | Completely delete all mirror world saves (requires restart) | OP (Level 3) |

## Configuration

Config file located at `config/instantworldmirror-common.toml`

### Dimension Pool Settings

| Config            | Default | Description                                                                 |
| ----------------- | ------- | --------------------------------------------------------------------------- |
| dimensionPoolSize | 4       | Mirror dimension pool size (1-8), determines max concurrent mirror sessions |

### World Copy Settings

| Config               | Default | Description                                               |
| -------------------- | ------- | --------------------------------------------------------- |
| copyChunkRadius      | 10      | Copy radius (chunks), total chunks = (radius\*2+1)^2      |
| copyChunksPerTick    | 2       | Chunks copied per tick, higher = faster but may cause lag |
| cleanupChunksPerTick | 4       | Chunks cleaned per tick                                   |
| edgeCleanupRadius    | 3       | Extra radius for BFS edge cleanup scan                    |

### Portal Settings

| Config               | Default | Description                                        |
| -------------------- | ------- | -------------------------------------------------- |
| entryPortalLifetime  | 300     | Entry portal duration (seconds), -1 for permanent  |
| returnPortalLifetime | -1      | Return portal duration (seconds), -1 for permanent |
| maxPortalLoadingTime | 600     | Maximum portal loading wait time (seconds)         |

### Item and Mob Settings

| Config                 | Default | Description                                                        |
| ---------------------- | ------- | ------------------------------------------------------------------ |
| allowItemTransfer      | false   | Whether to allow players to bring items back by default            |
| mirrorCooldown         | 300     | Dimension Mirror cooldown (seconds), reduced by Efficiency         |
| copyEntities           | false   | Whether to copy mob entities                                       |
| copyDecorationEntities | true    | Whether to copy decoration entities (paintings, item frames, etc.) |
| enableMobSpawning      | false   | Whether to allow natural mob spawning in mirror world              |

### Environment Settings

| Config         | Default | Description                    |
| -------------- | ------- | ------------------------------ |
| copyBiomes     | true    | Whether to copy biome data     |
| copyStructures | true    | Whether to copy structure data |
| copyHeightmaps | true    | Whether to copy heightmap data |

### Server Limits

| Config                      | Default | Description                                    |
| --------------------------- | ------- | ---------------------------------------------- |
| maxMirrorWorldsPerPlayer    | 1       | Maximum concurrent mirror sessions per player  |
| staleSessionCleanupInterval | 300     | Stale session cleanup check interval (seconds) |

### Client Settings

Client config located at `config/instantworldmirror-client.toml`

| Config          | Default | Description                                |
| --------------- | ------- | ------------------------------------------ |
| showCooldownHud | false   | Show cooldown timer HUD in top-left corner |

## Gameplay

1. Craft a Dimension Mirror
2. Go to the area you want to copy
3. Right-click a solid block to create an entry portal
4. Wait for world copy to complete (portal shows loading progress)
5. Enter the portal to start exploring and experimenting in the mirror world
6. Use Dimension Mirror to create a return portal, or use `/iwm return` command
7. Mirror world automatically cleans up after you leave, ready for next use

## Important Notes

- By default, items obtained in the mirror world are NOT kept when returning
- Cannot build Nether or End portals in the mirror world
- Each new mirror session copies the world fresh
- Changes in mirror world do NOT affect the source world
- Server admins have full control over player access permissions

## Downloads

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/instantworldmirror) - Mod download
- [GitHub](https://github.com/crabsatellite/InstantWorldMirror) - Source code

## License

Apache License 2.0
