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
| `active_use_lifecycle` | `block_use` | `covered` | `item.block_use_order`, `item.cooldown_applies_after_success` |
| `active_use_lifecycle` | `air_hold` | `covered` | `item.air_use_order`, `item.finish_use_packet_scope` |
| `active_use_lifecycle` | `cooldown` | `covered` | `item.cooldown_efficiency_only`, `item.cooldown_ignores_permanence` |
| `temporary.session_lifecycle` | `create` | `covered` | `manager.create_session_order` |
| `temporary.session_lifecycle` | `enter` | `covered` | `manager.teleport_session_order` |
| `temporary.session_lifecycle` | `return` | `covered` | `manager.return_temporary_order` |
| `temporary.session_lifecycle` | `heaven_origin_gate` | `covered` | `manager.heaven_return_origin_gate` |
| `death_external_logout_lifecycle` | `respawn` | `covered` | `events.respawn_restore_order` |
| `death_external_logout_lifecycle` | `external_exit` | `covered` | `events.dimension_change_exit_order` |
| `death_external_logout_lifecycle` | `logout` | `covered` | `events.logout_cleanup_order` |
| `persistent.record_lifecycle` | `save` | `covered` | `manager.save_persistent_order` |
| `persistent.record_lifecycle` | `enter` | `covered` | `manager.enter_persistent_order` |
| `persistent.record_lifecycle` | `leave` | `covered` | `manager.leave_persistent_order` |
| `persistent.record_lifecycle` | `delete` | `covered` | `manager.delete_persistent_order` |
| `persistent.pool_lifecycle` | `dimensions` | `covered` | `dimensions.persistent_json_count`, `dimensions.persistent_json_values`, `dimensions.separate_pool` |
| `persistent.pool_lifecycle` | `copy` | `covered` | `copy.persistent_queue`, `copy.persistent_queue_order`, `copy.persistent_completion_order`, `copy.no_portal_entity_copy` |
| `persistent.pool_lifecycle` | `worldgen` | `covered` | `worldgen.structures_disabled` |
| `command_bypass_guards` | `menu` | `covered` | `command.menu_uses_held_mirror` |
| `command_bypass_guards` | `save` | `covered` | `manager.save_requires_persistent_access` |
| `command_bypass_guards` | `enter` | `covered` | `manager.enter_requires_matching_permanence` |
| `runtime_smoke_infra` | `script` | `covered` | `runtime.runclient_smoke_script` |
