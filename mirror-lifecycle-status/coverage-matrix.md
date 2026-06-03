# Mirror lifecycle coverage matrix

| Route | Stage | Status | Checks |
| --- | --- | --- | --- |
| `temporary.dimension_mirror` | `entry` | `covered` | `item.entry.passes_permanence_state`, `session.persistent_access_field` |
| `temporary.dimension_mirror` | `save_gate` | `covered` | `manager.save_requires_persistent_access` |
| `temporary.heaven_mirror` | `kind` | `covered` | `item.kind_from_stack`, `record.kind_saved_from_session` |
| `temporary.heaven_mirror` | `sandbox` | `covered` | `sandbox.prep_receives_persistent_access` |
| `temporary.first_dream_mirror` | `kind` | `covered` | `item.kind_from_stack`, `kind.first_dream_present`, `kind.first_dream_not_sandbox`, `kind.first_dream_pristine`, `enchantment.renewal_first_dream_only`, `enchantment.renewal_hard_to_get_registration` |
| `temporary.first_dream_mirror` | `copy` | `covered` | `session.kind_field`, `copy.pristine_task_flag`, `copy.pristine_generator_features`, `copy.pristine_generator_pipeline`, `copy.pristine_scratch_world_skips_poi_updates`, `copy.pristine_loot_uses_mob_spawn_config`, `session.generated_content_refresh_field`, `copy.generated_content_refresh_task_flag`, `events.generated_content_refresh_allows_mob_spawn` |
| `permanent.dimension_mirror` | `enchantment` | `covered` | `enchantment.helper_present`, `enchantment.version_registration`, `enchantment.mirror_item_support` |
| `permanent.dimension_mirror` | `enter_gate` | `covered` | `manager.enter_requires_matching_permanence`, `command.menu_uses_held_mirror` |
| `permanent.heaven_mirror` | `sandbox_hotbar` | `covered` | `sandbox.applies_permanence_to_hotbar_mirror` |
| `permanent.heaven_mirror` | `persistent_enter` | `covered` | `manager.persistent_enter_passes_access` |
| `active_use_lifecycle` | `block_use` | `covered` | `item.block_use_order`, `item.cooldown_applies_after_success` |
| `active_use_lifecycle` | `air_hold` | `covered` | `item.air_use_order`, `item.finish_use_packet_scope` |
| `active_use_lifecycle` | `cooldown` | `covered` | `item.cooldown_efficiency_only`, `item.cooldown_ignores_permanence`, `item.renewal_item_cooldown`, `item.renewal_cooldown_icon_visible`, `item.renewal_cooldown_tooltip_visible`, `item.renewal_creative_bypasses_cooldown` |
| `temporary.session_lifecycle` | `create` | `covered` | `manager.create_session_order` |
| `temporary.session_lifecycle` | `enter` | `covered` | `manager.teleport_session_order` |
| `temporary.session_lifecycle` | `return` | `covered` | `manager.return_temporary_order` |
| `temporary.session_lifecycle` | `heaven_origin_gate` | `covered` | `manager.heaven_return_origin_gate` |
| `death_external_logout_lifecycle` | `respawn` | `covered` | `events.respawn_restore_order` |
| `death_external_logout_lifecycle` | `external_exit` | `covered` | `events.dimension_change_exit_order` |
| `death_external_logout_lifecycle` | `login` | `covered` | `events.login_saved_origin_restore_order`, `events.login_clears_persistent_tracking` |
| `death_external_logout_lifecycle` | `logout` | `covered` | `events.logout_cleanup_order`, `events.logout_clears_persistent_tracking` |
| `death_external_logout_lifecycle` | `shutdown` | `covered` | `events.server_stop_clears_all_lifecycle_state`, `dimension_pool.allocate_marks_cleanup_dirty`, `manager.server_stop_marks_active_dimensions`, `dimension_pool.cleaning_removes_allocations` |
| `persistent.record_lifecycle` | `save` | `covered` | `manager.save_persistent_order`, `manager.save_retains_temporary_source`, `manager.copy_completion_releases_temporary_source`, `manager.save_rejects_duplicate_session`, `record.source_session_persisted`, `data.source_session_lookup`, `record.selector_present`, `data.selector_lookup` |
| `persistent.record_lifecycle` | `enter` | `covered` | `manager.enter_persistent_order` |
| `persistent.record_lifecycle` | `rename` | `covered` | `manager.rename_record_order`, `manager.menu_uses_selectors`, `client.persistent_popup_controls` |
| `persistent.record_lifecycle` | `leave` | `covered` | `manager.leave_persistent_order` |
| `persistent.record_lifecycle` | `delete` | `covered` | `manager.delete_persistent_order`, `manager.delete_releases_unready_temporary_source`, `copy.persistent_cancel_task` |
| `persistent.record_lifecycle` | `recover_unready` | `covered` | `manager.recover_unready_records`, `manager.recover_releases_unready_temporary_source`, `data.remove_unready_records`, `startup.recover_unready_records` |
| `persistent.pool_lifecycle` | `dimensions` | `covered` | `dimensions.persistent_json_count`, `dimensions.persistent_json_values`, `dimensions.separate_pool` |
| `persistent.pool_lifecycle` | `copy` | `covered` | `copy.persistent_queue`, `copy.persistent_queue_order`, `copy.persistent_completion_order`, `copy.no_portal_entity_copy` |
| `persistent.pool_lifecycle` | `worldgen` | `covered` | `worldgen.structures_disabled` |
| `command_bypass_guards` | `menu` | `covered` | `command.menu_uses_held_mirror` |
| `command_bypass_guards` | `save` | `covered` | `manager.save_requires_persistent_access` |
| `command_bypass_guards` | `enter` | `covered` | `manager.enter_requires_matching_permanence` |
| `command_bypass_guards` | `selection` | `covered` | `command.persistent_record_suggestions`, `command.persistent_uses_selector`, `command.persistent_rename_command`, `lang.persistent_messages` |
| `command_bypass_guards` | `admin_visibility` | `covered` | `command.status_includes_persistent_pool`, `command.persistent_name_suggestions_all_kinds`, `command.mob_updates_persistent_worlds`, `command.repair_scans_dead_data_only`, `command.purge_temporary_pool_only`, `manager.repair_dead_persistent_records_only`, `worldcopy.persistent_copy_query`, `lang.repair_messages`, `lang.status_persistent_messages` |
| `runtime_smoke_infra` | `script` | `covered` | `runtime.runclient_smoke_script` |
| `runtime.gametest_lifecycle` | `registration` | `covered` | `runtime.gametest_source_registered`, `runtime.gametest_empty_template` |
| `runtime.gametest_lifecycle` | `coverage` | `covered` | `runtime.gametest_behavior_coverage`, `runtime.gametest_pristine_terrain_coverage` |
| `runtime.gametest_lifecycle` | `script` | `covered` | `runtime.gametest_smoke_script` |
| `packaging_lifecycle` | `jar_contents` | `covered` | `build.verify_mirror_jar_contents` |
