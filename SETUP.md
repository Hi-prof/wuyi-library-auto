# 吾忆图书馆自动化 - 环境配置指南

## 必需环境

### 1. Java 17 (JDK 17)

项目需要 Java 17，推荐使用 OpenJDK 或 Oracle JDK。

**Windows 安装方式：**

#### 方式一：使用 Scoop（推荐）
```bash
# 安装 Scoop（如果尚未安装）
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression

# 安装 Java 17
scoop bucket add java
scoop install openjdk17
```

#### 方式二：手动下载
1. 下载 OpenJDK 17: https://adoptium.net/zh-CN/temurin/releases/?version=17
2. 选择 Windows x64 的 MSI 安装包
3. 运行安装程序，勾选"设置 JAVA_HOME 环境变量"
4. 验证安装：
```bash
java -version
# 应该显示类似：openjdk version "17.0.x"
```

### 2. Android SDK

#### 方式一：通过 Android Studio（推荐）
1. 下载并安装 [Android Studio](https://developer.android.com/studio)
2. 启动 Android Studio
3. 进入 Settings → Appearance & Behavior → System Settings → Android SDK
4. 安装以下组件：
   - Android SDK Platform 34 (compileSdk)
   - Android SDK Build-Tools 34.0.0+
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools

5. 配置环境变量 `ANDROID_HOME`：
   - 变量名: `ANDROID_HOME`
   - 变量值: Android SDK 安装路径（通常是 `C:\Users\你的用户名\AppData\Local\Android\Sdk`）

6. 添加到 PATH：
   - `%ANDROID_HOME%\platform-tools`
   - `%ANDROID_HOME%\cmdline-tools\latest\bin`

#### 方式二：仅安装 Command-line Tools（轻量级）
1. 下载 [Android Command Line Tools](https://developer.android.com/studio#command-line-tools-only)
2. 解压到任意目录，如 `C:\Android\cmdline-tools`
3. 配置 `ANDROID_HOME` 环境变量
4. 使用 sdkmanager 安装必需组件：
```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### 3. Gradle

**无需手动安装** - 项目自带 Gradle Wrapper (gradlew)，会自动下载 Gradle 8.7。

## 可选配置

### 创建 local.properties（如果 Android SDK 不在标准路径）

在 `library-android` 目录下创建 `local.properties` 文件：
```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```
注意：Windows 路径中的反斜杠需要转义为 `\\`

### 创建 keystore.properties（仅用于 Release 签名）

如果需要构建签名的 Release APK，在 `library-android` 目录下创建 `keystore.properties`：
```properties
storeFile=path/to/your/keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

**如果只开发调试版本，可以跳过此步骤。**

## 构建项目

### 初次构建
```bash
cd library-android

# Windows
gradlew.bat assembleDebug

# 或使用 Git Bash
./gradlew assembleDebug
```

### 常用命令
```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（需要签名配置）
./gradlew assembleRelease

# 清理构建
./gradlew clean

# 运行测试
./gradlew test

# 安装到连接的设备
./gradlew installDebug
```

### 构建产物位置
- Debug APK: `library-android/app/build/outputs/apk/debug/`
- Release APK: `library-android/app/build/outputs/apk/release/`
- 自动复制到: `../dist/android/` （带版本号的文件名）

## 项目信息

- **CompileSdk**: 34
- **MinSdk**: 26 (Android 8.0)
- **TargetSdk**: 34
- **Kotlin**: 1.9.24
- **Gradle**: 8.7
- **AGP**: 8.5.2
- **Java**: 17

## 国内网络优化

项目已配置阿里云 Maven 镜像，无需额外配置。如果遇到网络问题，确保 `settings.gradle.kts` 中的镜像配置正确。

## 故障排查

### 问题：gradlew: command not found
**解决**: 确保在 `library-android` 目录下执行命令，或使用 `./gradlew` 而不是 `gradlew`

### 问题：JAVA_HOME is not set
**解决**: 配置 JAVA_HOME 环境变量指向 JDK 17 安装目录

### 问题：SDK location not found
**解决**: 创建 `local.properties` 文件并设置 `sdk.dir`

### 问题：依赖下载失败
**解决**: 检查网络连接，或尝试使用 VPN。项目已配置阿里云镜像，国内网络通常没问题。

## 开发环境推荐

- **IDE**: Android Studio 或 IntelliJ IDEA
- **设备**: Android 8.0+ 真机或模拟器
- **Git**: 用于版本控制
