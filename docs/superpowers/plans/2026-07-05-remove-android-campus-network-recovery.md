# Remove Android Campus Network Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Android active Wi-Fi reconnect and campus captive-portal auto-auth recovery while keeping normal login, reservation, sign-in, sync, diagnostics, and scheduling behavior.

**Architecture:** First update tests and integration boundaries so removed settings and runtime hooks are expected to disappear. Then delete the now-unreferenced UI, storage, and runtime classes. Finish with reference scans and focused Android verification.

**Tech Stack:** Android/Kotlin, Jetpack Compose, Gradle, JUnit/Robolectric, WorkManager.

## Global Constraints

- Android only; do not modify `library-window` or `library-fwq`.
- Do not remove normal school account login or seat-system HTTP authentication.
- Do not change background periodic check-in scheduling, watchdog scheduling, diagnostics, or server sync.
- Do not migrate or scrub old saved preferences; abandoned keys may remain on existing devices.
- Preserve existing unrelated user edits in dirty files.

---

### Task 1: Update Settings And Navigation Expectations

**Files:**
- Modify: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/SettingsMenuModelsTest.kt`
- Modify: `library-android/app/src/androidTest/java/com/wuyi/libraryauto/SmokeNavigationTest.kt`

**Interfaces:**
- Consumes: existing `settingsDestinations` and app navigation routes.
- Produces: tests that fail while Wi-Fi reconnect, campus-network auth, and network monitoring entries still exist.

- [ ] **Step 1: Write failing tests**

Update `SettingsMenuModelsTest` to assert that `settingsDestinations.map { it.route }` does not include:

```kotlin
SettingsWifiReconnectRoute
SettingsCampusNetworkRoute
SettingsNetworkMonitoringRoute
```

Update the smoke navigation test so it no longer attempts to open removed settings destinations.

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wuyi.libraryauto.ui.screen.settings.SettingsMenuModelsTest"
```

Expected: FAIL because the removed settings destinations are still present.

- [ ] **Step 3: Implement settings removal**

Modify `SettingsMenuModels.kt` to remove:

```kotlin
const val SettingsWifiReconnectRoute = "settings/wifi-reconnect"
const val SettingsCampusNetworkRoute = "settings/campus-network"
const val SettingsNetworkMonitoringRoute = "settings/network-monitoring"
```

Remove their destination entries and unused imports/icons.

Modify `AppNavGraph.kt` to remove:

```kotlin
AppRoutes.SettingsWifiReconnect
AppRoutes.SettingsCampusNetwork
AppRoutes.SettingsNetworkMonitoring
WifiReconnectSettingsScreen()
CampusNetworkScreen(...)
NetworkMonitoringScreen(...)
```

Remove the matching imports and dependency arguments.

- [ ] **Step 4: Run tests to verify pass**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wuyi.libraryauto.ui.screen.settings.SettingsMenuModelsTest"
```

Expected: PASS.

---

### Task 2: Remove App Dependency Wiring And Settings Screens

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/navigation/AppDependencies.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/SettingsDetailScreens.kt`
- Delete: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/CampusNetworkScreen.kt`
- Delete: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/CampusNetworkViewModel.kt`
- Delete: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/NetworkMonitoringScreen.kt`
- Delete: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/NetworkMonitoringViewModel.kt`
- Delete: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrar.kt`
- Delete: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrarTest.kt`

**Interfaces:**
- Consumes: Task 1 removes navigation callers.
- Produces: app dependency graph with no campus-network recovery stores, metrics screen repository, or portal authenticator.

- [ ] **Step 1: Remove dependency fields**

Delete these `AppDependencies` fields and imports:

```kotlin
CampusNetworkCredentialStore
WifiReconnectStore
CampusPortalAuthenticator
OkHttpTargetReachabilityProbe
NetworkMonitorMetricsRepository
ConnectivityManager
WifiManager
campusNetworkCredentialStore
campusPortalAuthenticator
campusPortalLoginPageUrlProvider
wifiReconnectStore
networkMonitorMetricsRepository
```

- [ ] **Step 2: Remove Wi-Fi reconnect composable from shared settings file**

In `SettingsDetailScreens.kt`, delete `WifiReconnectSettingsScreen` and imports used only by it:

```kotlin
WifiReconnectNetwork
WifiReconnectSnapshot
WifiReconnectStore
Switch
OutlinedTextField
```

Keep build info, permissions, runtime guide, automation guide, and diagnostics screens.

- [ ] **Step 3: Delete unreachable settings files and test**

Delete the five production settings files and the Wi-Fi suggestion registrar test listed above.

- [ ] **Step 4: Run a reference scan**

Run:

```powershell
rg -n "WifiReconnectSettingsScreen|CampusNetworkScreen|CampusNetworkViewModel|NetworkMonitoringScreen|NetworkMonitoringViewModel|WifiReconnectSuggestionRegistrar|CampusNetworkCredentialStore|WifiReconnectStore|NetworkMonitorMetricsRepository" library-android\app
```

Expected: no references in app production code; remaining references only in tests that will be removed or updated by later tasks.

---

### Task 3: Remove Background Recovery Startup And Runner Calls

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/WuyiLibraryApp.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/runtime/StorageBackedPeriodicCheckInRunner.kt`
- Delete: `library-android/app/src/main/java/com/wuyi/libraryauto/runtime/CampusPortalRecoveryRunnerFactory.kt`

**Interfaces:**
- Consumes: existing `PeriodicCheckInWorkerProvider.install { StorageBackedPeriodicCheckInRunner(context) }`.
- Produces: background check-in runner that does not attempt active Wi-Fi reconnect or campus portal auth before normal work.

- [ ] **Step 1: Write failing runtime reference check**

Use `rg` as the guard:

```powershell
rg -n "CaptivePortalRecoveryProvider|CampusPortalRecoveryRunnerFactory|BackgroundNetworkRecoveryCoordinator|ActiveWifiReconnector|AndroidWorkerNetworkManager|WifiReconnectStore|recoverIfNeeded|currentRecoverySettings|NetworkRecoveryEventBus" library-android\app\src\main
```

Expected before implementation: references are present.

- [ ] **Step 2: Remove application-level provider and event observer**

In `WuyiLibraryApp.kt`, remove:

```kotlin
CaptivePortalRecoveryProvider.install { context ->
    CampusPortalRecoveryRunnerFactory.create(context)
}
observeNetworkRecoveryEvents()
private fun observeNetworkRecoveryEvents()
applicationScope
NetworkRecoveryEventBus
CampusPortalRecoveryRunnerFactory
```

Keep WorkManager initialization, `PeriodicCheckInWorkerProvider.install`, `PeriodicCheckInWorker.ensureScheduled`, `WatchdogWorker.ensureScheduled`, `GuardSchedulerService.start`, and process restart handling.

- [ ] **Step 3: Remove runner recovery fields and call**

In `StorageBackedPeriodicCheckInRunner.kt`, delete:

```kotlin
private val networkRecoveryCoordinator = ...
private val wifiReconnectStore = WifiReconnectStore(appContext)
runCatching { networkRecoveryCoordinator.recoverIfNeeded(currentRecoverySettings()) }
private fun currentRecoverySettings(): WifiReconnectSettings
```

Remove imports for `ActiveWifiReconnector`, `AndroidWorkerNetworkManager`, `BackgroundNetworkRecoveryCoordinator`, `CaptivePortalRecoveryProvider`, and `WifiReconnectStore`.

- [ ] **Step 4: Delete portal recovery factory**

Delete `CampusPortalRecoveryRunnerFactory.kt`.

- [ ] **Step 5: Verify app main references are gone**

Run the `rg` command from Step 1 again.

Expected: no matches in `library-android/app/src/main`.

---

### Task 4: Delete Runtime And Storage Recovery Modules

**Files:**
- Delete: `library-android/core/storage/src/main/java/com/wuyi/libraryauto/core/storage/network/WifiReconnectStore.kt`
- Delete: `library-android/core/storage/src/main/java/com/wuyi/libraryauto/core/storage/network/CampusNetworkCredentialStore.kt`
- Delete: `library-android/core/storage/src/test/java/com/wuyi/libraryauto/core/storage/network/WifiReconnectStoreTest.kt`
- Delete: `library-android/core/storage/src/test/java/com/wuyi/libraryauto/core/storage/network/CampusNetworkCredentialStoreTest.kt`
- Delete: `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/network/ActiveWifiReconnector.kt`
- Delete: `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/network/BackgroundNetworkRecoveryCoordinator.kt`
- Delete: `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/network/CaptivePortalRecoveryProvider.kt`
- Delete: `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/network/NetworkRecoveryEventBus.kt`
- Delete: `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/network/WifiReconnectSettings.kt`
- Delete: `library-android/core/runtime/src/test/kotlin/com/wuyi/libraryauto/core/runtime/network/ActiveWifiReconnectorTest.kt`
- Delete: `library-android/core/runtime/src/test/kotlin/com/wuyi/libraryauto/core/runtime/network/BackgroundNetworkRecoveryCoordinatorActiveReconnectTest.kt`
- Delete: `library-android/core/runtime/src/test/kotlin/com/wuyi/libraryauto/core/runtime/network/BackgroundNetworkRecoveryCoordinatorTest.kt`
- Delete: `library-android/core/runtime/src/test/kotlin/com/wuyi/libraryauto/core/runtime/network/NetworkRecoveryEventBusTest.kt`

**Interfaces:**
- Consumes: Task 3 removes app production references.
- Produces: no active reconnect or captive portal recovery runtime API in Android modules.

- [ ] **Step 1: Delete files**

Delete the files listed above.

- [ ] **Step 2: Fix remaining runtime tests that used recovery result types**

If `ReservationGuardWorkerTestFakes.kt` or other worker tests import `NetworkRecoveryResult`, replace that fake dependency with the current worker interface expectation or remove the recovery branch if the worker no longer consumes it.

- [ ] **Step 3: Run reference scan**

Run:

```powershell
rg -n "WifiReconnect|CampusNetworkCredential|CaptivePortalRecovery|BackgroundNetworkRecovery|ActiveWifiReconnect|NetworkRecoveryEventBus|NetworkRecoveryResult|WorkerNetworkManager" library-android
```

Expected: no matches except docs or generic comments that should be deleted if user-facing.

---

### Task 5: Remove Manifest Permission And Verify

**Files:**
- Modify: `library-android/app/src/main/AndroidManifest.xml`
- Potentially modify: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/SettingsMenuModelsTest.kt`
- Potentially modify: `library-android/app/src/androidTest/java/com/wuyi/libraryauto/SmokeNavigationTest.kt`

**Interfaces:**
- Consumes: deleted active Wi-Fi reconnect implementation.
- Produces: Android manifest without active Wi-Fi modification permission.

- [ ] **Step 1: Remove Wi-Fi modification permission**

Delete:

```xml
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

Keep:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

- [ ] **Step 2: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wuyi.libraryauto.ui.screen.settings.SettingsMenuModelsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.wuyi.libraryauto.ui.screen.settings.SignInMonitoringViewModelTest"
.\gradlew.bat :core:runtime:testDebugUnitTest
.\gradlew.bat :core:storage:testDebugUnitTest
```

Expected: all pass. If the local Android SDK is unavailable, record the exact failure.

- [ ] **Step 3: Run compile-level verification**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: build success. If the local Android SDK is unavailable, record the exact failure.

- [ ] **Step 4: Final reference and status checks**

Run:

```powershell
rg -n "WifiReconnect|CampusNetworkCredential|CaptivePortalRecovery|BackgroundNetworkRecovery|ActiveWifiReconnect|NetworkRecoveryEventBus|CHANGE_WIFI_STATE|校园网认证|Wi-Fi 重连|网络重连" library-android
git status --short
```

Expected: no production references to removed behavior; git status only shows intended Android removals plus pre-existing unrelated dirty files.
