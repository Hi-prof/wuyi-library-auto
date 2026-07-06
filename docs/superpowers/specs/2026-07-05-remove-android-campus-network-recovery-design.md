# Remove Android Campus Network Recovery Design

## Goal

Remove the Android app's campus-network recovery feature set so the app no longer actively reconnects Wi-Fi networks or automatically authenticates a campus captive portal during background work.

## Scope

Remove these Android-only capabilities:

- Active Wi-Fi reconnect configuration, storage, network suggestions, runtime reconnect attempts, and metrics display.
- Campus captive-portal credential storage, settings UI, automatic background authentication runner, and application-level provider injection.
- Settings entries and navigation routes for Wi-Fi reconnect, campus-network authentication, and network monitoring.
- Android manifest permissions that are only required for active Wi-Fi modification.

Keep these capabilities:

- Normal HTTP access for login, seat lookup, reservation, sign-in, sync, and diagnostics.
- Background periodic check-in scheduling and watchdog behavior.
- Sign-in monitoring, permissions help, runtime guide, automation guide, diagnostics, build info, and server sync settings.
- Generic network-state reads needed by remaining Android behavior.

## Architecture

The removal should be done at the integration boundaries first: settings navigation, `AppDependencies`, `WuyiLibraryApp`, and `StorageBackedPeriodicCheckInRunner`. Once no production code references the recovery types, delete the now-unreachable storage/runtime/UI classes and their tests.

The background check-in runner should no longer construct or call `BackgroundNetworkRecoveryCoordinator`. If the current network is unavailable or captive-portal gated, the ordinary business requests should fail naturally through the existing login, seat, or sign-in error paths.

## Components

### Settings UI

Remove these destinations and routes:

- `SettingsWifiReconnectRoute`
- `SettingsCampusNetworkRoute`
- `SettingsNetworkMonitoringRoute`

Delete the related composables and view models:

- `WifiReconnectSettingsScreen`
- `CampusNetworkScreen`
- `CampusNetworkViewModel`
- `NetworkMonitoringScreen`
- `NetworkMonitoringViewModel`
- `WifiReconnectSuggestionRegistrar`

### Application Startup

Remove `CaptivePortalRecoveryProvider.install` from `WuyiLibraryApp`. Keep worker provider installation, scheduling, watchdog scheduling, guard service startup, diagnostics logging, and network-recovery event observation only if still referenced by remaining runtime code.

If `NetworkRecoveryEventBus` becomes unused after removing recovery coordinator calls, delete its observer wiring and implementation too.

### Background Check-In

Remove these from `StorageBackedPeriodicCheckInRunner`:

- `BackgroundNetworkRecoveryCoordinator`
- `ActiveWifiReconnector`
- `AndroidWorkerNetworkManager`
- `CaptivePortalRecoveryProvider`
- `WifiReconnectStore`
- `currentRecoverySettings`
- the pre-run `recoverIfNeeded(...)` call

The rest of `run(...)` should remain intact: remote reservation sync, automation plan runner, account loading, and `RunPeriodicCheckInUseCase`.

### Storage And Runtime

Delete storage and runtime classes once unused:

- `core/storage/.../network/WifiReconnectStore.kt`
- `core/storage/.../network/CampusNetworkCredentialStore.kt`
- `core/runtime/.../network/ActiveWifiReconnector.kt`
- `core/runtime/.../network/BackgroundNetworkRecoveryCoordinator.kt`
- `core/runtime/.../network/CaptivePortalRecoveryProvider.kt`
- `core/runtime/.../network/NetworkRecoveryEventBus.kt`
- `core/runtime/.../network/WifiReconnectSettings.kt`

Keep any generic network-monitoring implementation only if another remaining screen or worker uses it. Otherwise remove it with its tests.

### Manifest

Remove `android.permission.CHANGE_WIFI_STATE`, because active Wi-Fi reconnect is no longer supported. Keep `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_WIFI_STATE` unless verification proves they are unused by remaining code.

## Error Handling

No new error path is introduced. Without recovery, network failures should surface through existing repository/use-case exceptions and audit logging. User-facing settings should not mention Wi-Fi reconnect or campus-network automatic authentication after removal.

## Tests

Update or remove tests that assert the removed features exist:

- Settings menu and navigation tests should expect no Wi-Fi reconnect, campus-network auth, or network monitoring entries.
- Runtime tests should no longer cover active reconnect, captive portal recovery, or network-recovery event bus behavior.
- Storage tests for removed stores should be deleted.

Run focused Android tests for settings/navigation and runtime, then run an Android compile/test command if available in the local environment.

## Non-Goals

- Do not remove normal school account login or seat-system HTTP authentication.
- Do not change Windows or server implementations.
- Do not refactor unrelated settings or reservation flows.
- Do not migrate or scrub old saved preferences; abandoned keys may remain on existing devices.

## Open Decisions

None. The user selected full Android removal of the campus-network recovery feature set.
