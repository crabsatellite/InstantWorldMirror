# InstantWorldMirror - Instant World Mirror

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1+-orange.svg)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**English** | **[中文](https://github.com/crabsatellite/InstantWorldMirror/blob/1.21.1/docs/readme/chn/README.md)**

## What This Mod Does

InstantWorldMirror lets you create temporary or saved mirror worlds at your current location. You can explore, test builds, test combat, or inspect freshly generated terrain without changing the original world.

The mod has three mirror items. Each one sends you to a separate world, with different copy rules, game modes, save options, and terrain refresh rules.

## Choose a Mirror

- **World Reflection Mirror** creates the standard temporary copy around you.
- **Heaven Mirror** sends you to a temporary Creative-mode sandbox.
- **First Dream Mirror** recreates the area's original terrain from the current world seed, ignoring player-made changes in the original world.

Use World Reflection Mirror for normal testing, Heaven Mirror for Creative testing, and First Dream Mirror when you want to see what the area looked like before players changed it.

## Session Flow

- Right-click a solid block with any mirror to create an entry portal.
- When the portal finishes copying or generating the area, step in to enter the mirror world.
- Inside a World Reflection Mirror or First Dream Mirror world, right-click a solid block to create a return portal. If that portal is far from the entrance, it returns you to the matching position in the original world.
- Hold right-click in the air inside a mirror world to teleport back to that mirror world's entrance point without leaving it.
- Heaven Mirror worlds cannot create return portals; use the original entrance or `/iwm return`.
- If you die in a mirror world, you return to the original world and your saved player state is restored.
- Each player can have only one mirror session open at a time.
- Other players can use the same entry portal after the creator enters first; if the creator leaves a temporary session, everyone else is sent back too.
- If you disconnect and rejoin while the mod still has your mirror-world save state, it tries to restore your original-world location, inventory, and player state.
- Temporary mirror worlds clean themselves up after players leave.

## World Rules

A copied mirror world includes blocks, block entities, biomes, structures, heightmaps, and optional entities inside the configured chunk radius.

First Dream Mirror uses the current seed and chunk generator from the original world to regenerate terrain, structures, biomes, caves, surfaces, and features. It keeps your current game mode on entry and uses the same inventory rules as World Reflection Mirror.

Mob spawning is locked down by default: natural, structure, and spawner spawns are blocked, while player-triggered spawns such as spawn eggs, breeding, buckets, and commands still work. Admins can use config or `/iwm mob` to control that rule and whether unopened generated loot is kept. Weather, time, and dimension visual effects follow the original world. Survival and Adventure players cannot use dimension portals from mirror worlds, and Nether portal creation is blocked there.

By default, items found or created inside mirror worlds are not kept when you return. The mod only saves and restores vanilla inventory and ender chest; it does not deliberately clear modded accessory or capability inventories.

## Heaven Sandbox

When you enter with Heaven Mirror, the mod saves your inventory and player state, clears the sandbox inventory, switches you to Creative, then restores the saved state when you leave.

Heaven Mirror also gives you a matching mirror in the sandbox, keeping Permanence if the mirror had it, so the menu and return flow still work.

## Enchantments and Cooldowns

- Using a mirror in Survival has a configurable cooldown. Efficiency reduces both the normal mirror cooldown and the Renewal cooldown by 20% per level, down to a 30 second minimum.
- Creative mode ignores both the normal mirror cooldown and the Renewal cooldown.
- With Permanence, a mirror can save a completed temporary mirror as a persistent mirror if the player is an operator or has been granted permission.
- To save a persistent mirror, the temporary session must have been created with a mirror that has Permanence. To enter that saved mirror later, hold a mirror of the same type with Permanence.
- Renewal only works on First Dream Mirror. If natural mob spawning is off in config or through `/iwm mob off`, a Renewal First Dream Mirror can refresh generated mobs and unopened generated loot after its cooldown.
- The normal mirror cooldown belongs to the player. The Renewal cooldown belongs to the First Dream Mirror item. The item bar and tooltip show Renewal cooldown, and `showCooldownHud` can show the normal mirror cooldown.
- The World Mirrors creative tab includes all three mirrors plus Permanence and Renewal enchanted books.
- In Survival, Permanence comes from regular enchanting and villager trading; Renewal is rare loot.

## Persistent Mirrors

Persistent mirrors do not use the temporary session slots. They use a separate pool with eight server-wide slots.

A completed temporary mirror can be saved only if it was created with a mirror that has Permanence. The saved mirror remembers its mirror type, and entering it later requires a mirror of that same type with Permanence.

Shift-right-click a Permanence-enchanted mirror to open the centered menu for saving, entering, leaving, renaming, or deleting persistent mirrors. Esc or the X button closes it. Commands suggest selectors such as `slot_1`, so players do not need to copy UUIDs.

`/iwm status` reports both temporary slots and persistent slots. `/iwm repair` scans for interrupted mirror data and skips mirrors that are still in use. Persistent mirrors stay until their owner or an operator deletes them.

Interrupted persistent saves are repaired on server startup or with `/iwm repair`. `/iwm purge` deletes only temporary mirror world folders, never deletes persistent mirror records, and blocks new mirror creation until the required restart.

## Administration and Cleanup

- Heightmap scanning skips empty air columns during copy.
- Sequential chunk queues reduce server load.
- Cleanup tracks changed chunks and scans edge chunks for blocks that extend beyond the copy radius.
- Server shutdown saves pending cleanup state and clears temporary mirror session tracking.

## Installation

1. Install NeoForge for Minecraft 1.21.1.
2. Download this mod JAR file.
3. Place it in the `.minecraft/mods` folder.
4. Launch the game.

## Crafting Recipes

World Reflection Mirror: glass, obsidian, and an ender pearl.

```
[Glass]    [Obsidian]    [Glass]
[Obsidian] [Ender Pearl] [Obsidian]
[Glass]    [Obsidian]    [Glass]
```

Heaven Mirror: glowstone, redstone, and an ender pearl.

```
[Glowstone]     [Redstone Dust] [Glowstone]
[Redstone Dust] [Ender Pearl]   [Redstone Dust]
[Glowstone]     [Redstone Dust] [Glowstone]
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
| `/iwm return` | Return to the original world from a mirror world | All players |
| `/iwm mob on/off/status` | Toggle natural mob spawning and generated loot preservation in mirror worlds | OP level 2 |
| `/iwm allow <player>` | Allow a player to use mirror worlds | OP level 2 |
| `/iwm deny <player>` | Deny a player from using mirror worlds | OP level 2 |
| `/iwm itemtransfer <player> <true/false>` | Set whether a player can bring items back | OP level 2 |
| `/iwm status` | Show temporary and persistent mirror slots | OP level 2 |
| `/iwm persistent menu` | Open the persistent mirror menu | Player |
| `/iwm persistent save [name]` | Save your current temporary mirror as persistent | OP level 3 or granted player |
| `/iwm persistent enter <selector>` | Enter a persistent mirror by suggested selector | Player with access |
| `/iwm persistent leave` | Leave the current persistent mirror | Player |
| `/iwm persistent delete <selector>` | Delete a persistent mirror by suggested selector | Owner or OP level 3 |
| `/iwm persistent rename <selector> <name>` | Rename a persistent mirror by suggested selector | Owner or OP level 3 |
| `/iwm persistent grant <player>` | Grant persistent mirror creation | OP level 3 |
| `/iwm persistent revoke <player>` | Revoke persistent mirror creation | OP level 3 |
| `/iwm repair` | Repair interrupted mirror data without clearing active mirrors | OP level 3 |
| `/iwm forceclear <dimension>` | Force-clear a temporary mirror dimension | OP level 3 |
| `/iwm purge` | Delete temporary mirror world folders and block new mirror creation until restart | OP level 3 |

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
| `maxMirrorWorldsPerPlayer` | 1 | Reserved session limit setting; current behavior allows one active session per player |
| `staleSessionCleanupInterval` | 300 | Stale session cleanup interval in seconds |

### Client Settings

Client config file: `config/instantworldmirror-client.toml`

| Config | Default | Description |
| --- | --- | --- |
| `showCooldownHud` | false | Show cooldown timer HUD |

## Downloads

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/instantworldmirror) - Mod download
- [GitHub](https://github.com/crabsatellite/InstantWorldMirror) - Source code

## License

Apache License 2.0
