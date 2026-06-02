# Mirror lifecycle route index

Complete: `true`

## temporary.dimension_mirror - covered

Unenchanted Dimensional Mirror stays temporary

- `entry`: covered - Entry portal records whether the held mirror has permanence
- `save_gate`: covered - Temporary sessions without permanence cannot be saved through commands

## temporary.heaven_mirror - covered

Unenchanted Heaven Mirror stays temporary

- `kind`: covered - Mirror kind is still derived from the two mirror items
- `sandbox`: covered - Sandbox inventory setup is parameterized by permanence

## permanent.dimension_mirror - covered

Permanence enchanted Dimensional Mirror can use persistent worlds

- `enchantment`: covered - Permanence enchantment is available for mirror items
- `enter_gate`: covered - Persistent world entry requires a matching enchanted mirror

## permanent.heaven_mirror - covered

Permanence enchanted Heaven Mirror keeps the enchanted mirror in sandbox

- `sandbox_hotbar`: covered - Creative sandbox hotbar mirror preserves permanence
- `persistent_enter`: covered - Entering a saved heaven mirror keeps persistent access for sandbox setup

## active_use_lifecycle - covered

Mirror item interactions keep cooldown, menu, portal, and hold-return behavior scoped

- `block_use`: covered - Block interaction validates access and space before creating portals and applying cooldown
- `air_hold`: covered - Air interaction only starts hold-to-return inside mirror worlds
- `cooldown`: covered - Cooldown reduction remains tied to Efficiency and not Permanence

## temporary.session_lifecycle - covered

Temporary mirror sessions save, enter, return, and clean up state in order

- `create`: covered - Session creation refuses purge/duplicate sessions before allocating a temporary dimension
- `enter`: covered - Session entry saves origin and inventory before teleport and return portal spawn
- `return`: covered - Temporary return restores inventory, bypasses own teleport guards, and destroys finished sessions
- `heaven_origin_gate`: covered - Heaven mirror return portals can only return through the original entrance area

## death_external_logout_lifecycle - covered

Death, external dimension changes, and logout restore or clean mirror state without duplication

- `respawn`: covered - Mirror-world death restoration is deferred to respawn and forces overworld recovery
- `external_exit`: covered - External dimension changes route persistent and temporary exits through the correct cleanup handlers
- `logout`: covered - Logout saves cooldown before session cleanup and local tracking cleanup

## persistent.record_lifecycle - covered

Persistent mirror records save, enter, leave, and delete through closed lifecycle gates

- `save`: covered - Saving a persistent mirror checks permission, session permanence, copy completion, slot allocation, and queueing
- `enter`: covered - Entering a persistent mirror checks readiness, matching enchanted mirror, access rights, and active-session exclusivity
- `leave`: covered - Leaving a persistent mirror restores saved state before teleport cleanup and effect clearing
- `delete`: covered - Deleting a persistent mirror ejects players, cleans the persistent world, and removes the record

## persistent.pool_lifecycle - covered

Persistent worlds use a separate pool and lifecycle

- `dimensions`: covered - Eight persistent dimensions are registered independently
- `copy`: covered - Persistent copy queue is separate from temporary cleanup
- `worldgen`: covered - Mirror worlds do not generate external structures

## command_bypass_guards - covered

Commands cannot bypass the enchanted-mirror lifecycle

- `menu`: covered - Menu command resolves the held mirror stack
- `save`: covered - Save command is blocked unless the current session was permanence-enabled
- `enter`: covered - Enter command is blocked unless the player still holds a matching enchanted mirror

## runtime_smoke_infra - covered

runClient smoke verification is reproducible from repo infra

- `script`: covered - Smoke script launches runClient, waits for render/audio evidence, and cleans temporary processes/logs
