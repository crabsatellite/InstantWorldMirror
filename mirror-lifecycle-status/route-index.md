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
