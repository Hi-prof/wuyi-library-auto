import java.util.Properties
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
}

val releaseSigningKeys =
    listOf(
        "storeFile",
        "storePassword",
        "keyAlias",
        "keyPassword",
    )
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}
val versionProperties = Properties()
val versionPropertiesFile = rootProject.file("version.properties")
if (versionPropertiesFile.exists()) {
    versionPropertiesFile.inputStream().use(versionProperties::load)
}
fun isConfiguredReleaseValue(key: String): Boolean {
    val value = keystoreProperties.getProperty(key)?.trim().orEmpty()
    return value.isNotEmpty() && !value.contains("<")
}
fun readVersionProperty(key: String, fallback: String): String =
    versionProperties.getProperty(key)?.trim().orEmpty().ifBlank { fallback }

val releaseStoreFilePath = keystoreProperties.getProperty("storeFile")?.trim().orEmpty()
val releaseStoreFile =
    releaseStoreFilePath
        .takeIf(String::isNotEmpty)
        ?.let(rootProject::file)
val distAndroidDir = rootProject.layout.projectDirectory.dir("../dist/android")
val appVersionCode = readVersionProperty("versionCode", "226").toInt()
val appVersionName = readVersionProperty("versionName", "3.1.4")
val appBuildMarker = readVersionProperty("buildMarker", "2026-05-16-092000")
val hasReleaseSigning =
    keystorePropertiesFile.exists() &&
        releaseSigningKeys.all(::isConfiguredReleaseValue) &&
        releaseStoreFile != null &&
        releaseStoreFile.exists()

android {
    namespace = "com.wuyi.libraryauto"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "com.wuyi.libraryauto"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_MARKER", "\"$appBuildMarker\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.androidx.compose.compiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // account-pool-tri-sync 任务 12.2：Robolectric 跑 Room in-memory / WorkManager 测试时
        // 需要访问 AndroidManifest 与资源，开启此选项让 :guard 进程外的单元测试也能起 Application。
        unitTests.isIncludeAndroidResources = true
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:runtime"))
    implementation(project(":core:storage"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    // account-pool-tri-sync 任务 12.14：RoomLocalAccountStore 使用 androidx.room.withTransaction
    // 把 SyncApplier 的多条写入收敛到单事务，避免半写。
    implementation(libs.androidx.room.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // BUG-CAPTIVE 修复：CampusPortalRecoveryRunnerFactory 需要 OkHttp 与 cookie jar
    // 来驱动校园网门户认证流程；core:network 已传递 OkHttp，但 app 直接构造 client
    // 需要显式声明依赖。
    implementation(libs.okhttp)

    // account-pool-tri-sync 任务 12.1：Active_Account_Sync_API / Automation_Task_Sync_API
    // 客户端通过 Retrofit + kotlinx.serialization 调用服务端，并由 OkHttp 拦截器统一注入
    // Bearer Token 与 HTTPS 强制（环回地址放行明文，便于本机联调）。
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.serialization.json)

    // account-pool-tri-sync 任务 12.3 / 12.8：AutomationTaskUploadWorker 直接构造
    // OneTimeWorkRequest、NetworkType 等 WorkManager API，app 模块需要直接依赖
    // work-runtime-ktx；core:runtime 虽然也声明了它，但只是 `implementation` 不会向上暴露。
    // 任务 12.8 已撤回 ActivePoolListSyncWorker（PeriodicWorkRequest 周期同步），
    // 该依赖现仅服务于 Automation_Task 上行 Worker。
    implementation(libs.androidx.work.runtime.ktx)

    // account-pool-tri-sync 任务 12.13：ServerSyncConfig 使用 EncryptedSharedPreferences
    // 持久化 base_url / bearer_token 等敏感字段；core:storage 与 core:runtime 通过
    // `implementation` 引入但未对外暴露，app 模块需要直接声明依赖。
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp)
    // account-pool-tri-sync 任务 12.2 / 12.3：AccountPoolSyncRepository 与
    // AutomationTaskUploadWorker 的单元测试需要 Robolectric 跑 Room in-memory + WorkManager
    // 测试初始化（任务 12.8 已撤回 ActivePoolListSyncWorker，本依赖仅留给上行 Worker 测试用）。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

val copyDebugApkToDist =
    tasks.register<Copy>("copyDebugApkToDist") {
        from(layout.buildDirectory.dir("outputs/apk/debug"))
        include("*.json")
        into(distAndroidDir)
    }

val copyVersionedDebugApkToDist =
    tasks.register<Copy>("copyVersionedDebugApkToDist") {
        from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        into(distAndroidDir)
        rename { "wuyi-library-auto-v${appVersionName}-${appBuildMarker}-debug.apk" }
    }

val copyReleaseArtifactsToDist =
    tasks.register<Copy>("copyReleaseArtifactsToDist") {
        from(layout.buildDirectory.dir("outputs/apk/release"))
        include("*.apk", "*.json")
        into(distAndroidDir)
    }

val copyVersionedReleaseApkToDist =
    tasks.register<Copy>("copyVersionedReleaseApkToDist") {
        from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
        into(distAndroidDir)
        rename { "wuyi-library-auto-v${appVersionName}-${appBuildMarker}-release.apk" }
    }

val copyBundleArtifactsToDist =
    tasks.register<Copy>("copyBundleArtifactsToDist") {
        from(layout.buildDirectory.dir("outputs/bundle/release"))
        include("*.aab")
        into(distAndroidDir)
    }

val copyVersionedBundleToDist =
    tasks.register<Copy>("copyVersionedBundleToDist") {
        from(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
        into(distAndroidDir)
        rename { "wuyi-library-auto-v${appVersionName}-${appBuildMarker}-release.aab" }
    }

val cleanDistAndroidArtifacts =
    tasks.register<Delete>("cleanDistAndroidArtifacts") {
        delete(
            fileTree(distAndroidDir) {
                include("*.apk", "*.aab", "*.json")
            },
        )
    }

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(cleanDistAndroidArtifacts)
    finalizedBy(copyDebugApkToDist, copyVersionedDebugApkToDist)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(cleanDistAndroidArtifacts)
    finalizedBy(copyReleaseArtifactsToDist, copyVersionedReleaseApkToDist)
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(cleanDistAndroidArtifacts)
    finalizedBy(copyBundleArtifactsToDist, copyVersionedBundleToDist)
}
