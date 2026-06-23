pluginManagement {
    repositories {
        // 优先国内镜像，规避 dl.google.com / repo.maven.apache.org 的 TLS 握手抖动；
        // 末尾保留官方源做兜底，镜像缺包时 Gradle 会按顺序回退。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "wuyi-library-android"
include(":app", ":core:network", ":core:storage", ":core:ble", ":core:domain", ":core:runtime")
