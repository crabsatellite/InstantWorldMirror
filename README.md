# InstantWorldMirror - Instant World Mirror

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.3+-orange.svg)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**English** | **[中文](https://github.com/crabsatellite/InstantWorldMirror/blob/1.20.1/docs/readme/chn/README.md)**

## Instant Sandbox - Explore Without Consequences

InstantWorldMirror creates temporary or persistent mirror worlds from the place you are standing. You can explore, test builds, try combat, or inspect generated terrain without changing the source world.

## Features

### Mirror Items

- **World Reflection Mirror** creates the default temporary mirror copy of the current area.
- **Heaven Mirror** creates a temporary mirror where you enter in Creative mode for testing.
- **First Dream Mirror** creates original terrain again from the current world seed, ignoring player-made changes in the source world.

### Portal Flow

- Right-click a solid block with any mirror to create an entry portal.
- Inside a normal mirror world, hold right-click with the World Reflection Mirror or First Dream Mirror to return to the entrance.
- Heaven Mirror worlds can only leave through the original entrance.
- Death in a mirror world returns the player to the source world and restores saved state.

### Cooldowns and Enchantments

- Survival use has a configurable cooldown; Efficiency reduces both normal mirror use cooldown and Renewal refresh cooldown by 20% per level, down to a 30 second minimum.
- Creative mode bypasses mirror use cooldowns and Renewal refresh cooldowns.
- A mirror enchanted with Permanence can save a completed temporary mirror as a persistent mirror, if the player is an operator or has been granted permission.
- Renewal can only be applied to the First Dream Mirror. When mob spawning and loot generation are disabled, an enchanted First Dream Mirror can refresh generated mobs and unopened generated loot after its cooldown.

### Mirror World Mechanics

- Mirror copy sessions copy blocks, block entities, biomes, structures, heightmaps, and optional entities inside the configured chunk radius.
- Natural mob spawning is disabled by default; existing mobs and generated loot can be controlled by config or commands.
- Weather, time, and dimension visual effects are synchronized from the source dimension.
- Nether and End portals are blocked inside mirror worlds.

### First Dream Terrain

- First Dream Mirror uses the source world's current seed and chunk generator to regenerate terrain, structures, biomes, carvers, surfaces, and features.
- First Dream Mirror keeps the player's current game mode on entry and uses the same inventory transfer rules as the World Reflection Mirror.

### Heaven Sandbox

- When entering with Heaven Mirror, the mod saves your inventory and state, clears the sandbox inventory, switches you to Creative, and restores the saved state when you leave.
- Heaven Mirror gives the player a matching mirror inside the sandbox so the menu and return flow remain usable.

### Persistent Mirrors

- Persistent mirror worlds use a separate persistent dimension pool from temporary mirror sessions.
- Shift-right-click opens a centered mirror menu with buttons to save, enter, leave, rename, or delete persistent mirrors; Esc or the X button closes it.
- Persistent mirror selectors such as `slot_1` are suggested by commands, so players do not need to copy raw UUIDs.
- The status command reports both temporary mirror slots and persistent mirror slots.
- The repair command scans for interrupted dead data and skips live player state.

### Performance and Cleanup

- Heightmap scanning skips empty air columns during copy.
- Section-level operations and sequential queues reduce server load.
- Cleanup tracks modified chunks and scans edge chunks for blocks that extend beyond the copy radius.
- Server shutdown saves pending cleanup state and clears transient mirror session tracking.

## Installation

1. Install Forge for Minecraft 1.20.1.
2. Download this mod JAR file.
3. Place it in the `.minecraft/mods` folder.
4. Launch the game.

## Crafting Recipes

World Reflection Mirror: glass, obsidian, and an ender pearl.

```
[Glass]    [Obsidian] [Glass]
[Obsidian] [Ender Pearl] [Obsidian]
[Glass]    [Obsidian] [Glass]
```

Heaven Mirror: glowstone, redstone, and an ender pearl.

```
[Glowstone]    [Redstone Dust] [Glowstone]
[Redstone Dust] [Ender Pearl]   [Redstone Dust]
[Glowstone]    [Redstone Dust] [Glowstone]
```

First Dream Mirror: oak saplings, glass, oak logs, and an ender pearl.

```
[Oak Sapling] [Glass]       [Oak Sapling]
[Oak Log]     [Ender Pearl] [Oak Log]
[Oak Sapling] [Glass]       [Oak Sapling]
```

## Commands

All commands start with `/iwm`.

| Command | Description | Permission |
| --- | --- | --- |
| `/iwm return` | Return to the source world from a mirror world | All players |
| `/iwm mob on/off/status` | Control natural mob spawning and generated loot preservation in mirror worlds | OP level 2 |
| `/iwm allow <player>` | Allow a player to use mirror worlds | OP level 2 |
| `/iwm deny <player>` | Deny a player from using mirror worlds | OP level 2 |
| `/iwm itemtransfer <player> <true/false>` | Control whether a player can bring items back | OP level 2 |
| `/iwm status` | Show temporary and persistent mirror slot status | OP level 2 |
| `/iwm persistent menu` | Open the persistent mirror menu | Player |
| `/iwm persistent save [name]` | Save the current temporary mirror as persistent | OP level 3 or granted player |
| `/iwm persistent enter <selector>` | Enter a persistent mirror by suggested selector | Player with access |
| `/iwm persistent leave` | Leave the current persistent mirror | Player |
| `/iwm persistent delete <selector>` | Delete a persistent mirror by suggested selector | Owner or OP level 3 |
| `/iwm persistent rename <selector> <name>` | Rename a persistent mirror by suggested selector | Owner or OP level 3 |
| `/iwm persistent grant <player>` | Grant persistent mirror creation | OP level 3 |
| `/iwm persistent revoke <player>` | Revoke persistent mirror creation | OP level 3 |
| `/iwm repair` | Automatically repair interrupted dead mirror data without clearing live state | OP level 3 |
| `/iwm forceclear <dimension>` | Force clear a temporary mirror dimension | OP level 3 |
| `/iwm purge` | Delete temporary mirror world save folders and require a restart | OP level 3 |

## Configuration

Config file: `config/instantworldmirror-common.toml`

### Dimension Pool Settings

| Config | Default | Description |
| --- | --- | --- |
| `dimensionPoolSize` | 4 | Temporary mirror dimension pool size, 1-8 |

### World Copy Settings

| Config | Default | Description |
| --- | --- | --- |
| `copyChunkRadius` | 10 | Copy radius in chunks |
| `copyChunksPerTick` | 2 | Chunks copied per tick |
| `cleanupChunksPerTick` | 4 | Chunks cleaned per tick |
| `edgeCleanupRadius` | 3 | Extra radius for edge cleanup scan |

### Portal Settings

| Config | Default | Description |
| --- | --- | --- |
| `entryPortalLifetime` | 300 | Entry portal lifetime in seconds after copy completes |
| `returnPortalLifetime` | -1 | Return portal lifetime in seconds |
| `maxPortalLoadingTime` | 600 | Maximum portal loading time in seconds |

### Item and Mob Settings

| Config | Default | Description |
| --- | --- | --- |
| `allowItemTransfer` | false | Whether players can bring mirror-world items back by default |
| `mirrorCooldown` | 300 | Mirror use cooldown in seconds, reduced by Efficiency |
| `copyEntities` | false | Whether to copy mob entities |
| `copyDecorationEntities` | true | Whether to copy decoration entities |
| `enableMobSpawning` | false | Whether natural mob spawning is allowed in mirror worlds |

### Environment Settings

| Config | Default | Description |
| --- | --- | --- |
| `copyBiomes` | true | Copy biome data |
| `copyStructures` | true | Copy structure data |
| `copyHeightmaps` | true | Copy heightmap data |

### Server Limits

| Config | Default | Description |
| --- | --- | --- |
| `maxMirrorWorldsPerPlayer` | 1 | Maximum concurrent mirror sessions per player |
| `staleSessionCleanupInterval` | 300 | Stale session cleanup interval in seconds |

### Client Settings

Client config file: `config/instantworldmirror-client.toml`

| Config | Default | Description |
| --- | --- | --- |
| `showCooldownHud` | false | Show cooldown timer HUD |

## Notes

- By default, items gained in mirror worlds are not kept when returning.
- Temporary mirror worlds clean themselves up after players leave.
- Persistent mirror worlds stay available until deleted by their owner or an operator.
- Existing modded accessory or capability inventories are not intentionally cleared by this mod; only vanilla inventory and ender chest are saved and restored.

## Downloads

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/instantworldmirror) - Mod download
- [GitHub](https://github.com/crabsatellite/InstantWorldMirror) - Source code

## License

Apache License 2.0
