# Mirror lifecycle coverage matrix

| Route | Stage | Status | Checks |
| --- | --- | --- | --- |
| `temporary.dimension_mirror` | `entry` | `covered` | `item.entry.passes_permanence_state`, `session.persistent_access_field` |
| `temporary.dimension_mirror` | `save_gate` | `covered` | `manager.save_requires_persistent_access` |
| `temporary.heaven_mirror` | `kind` | `covered` | `item.kind_from_stack`, `record.kind_saved_from_session` |
| `temporary.heaven_mirror` | `sandbox` | `covered` | `sandbox.prep_receives_persistent_access` |
| `permanent.dimension_mirror` | `enchantment` | `covered` | `enchantment.helper_present`, `enchantment.version_registration`, `enchantment.mirror_item_support` |
| `permanent.dimension_mirror` | `enter_gate` | `covered` | `manager.enter_requires_matching_permanence`, `command.menu_uses_held_mirror` |
| `permanent.heaven_mirror` | `sandbox_hotbar` | `covered` | `sandbox.applies_permanence_to_hotbar_mirror` |
| `permanent.heaven_mirror` | `persistent_enter` | `covered` | `manager.persistent_enter_passes_access` |
| `persistent.pool_lifecycle` | `dimensions` | `covered` | `dimensions.persistent_json_count`, `dimensions.separate_pool` |
| `persistent.pool_lifecycle` | `copy` | `covered` | `copy.persistent_queue`, `copy.no_portal_entity_copy` |
| `persistent.pool_lifecycle` | `worldgen` | `covered` | `worldgen.structures_disabled` |
| `command_bypass_guards` | `menu` | `covered` | `command.menu_uses_held_mirror` |
| `command_bypass_guards` | `save` | `covered` | `manager.save_requires_persistent_access` |
| `command_bypass_guards` | `enter` | `covered` | `manager.enter_requires_matching_permanence` |
