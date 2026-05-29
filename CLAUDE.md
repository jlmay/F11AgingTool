# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug APK to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumentation tests (connected device needed)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

## Project Overview

F11-N 底座老化测试工具 — Android 老化测试工具，对 F11-N 底座进行循环压力测试。用 Kotlin 编写，单 Activity 架构。

### 核心架构

- **MainActivity** — 唯一 Activity，管理全部测试逻辑和 UI。测试循环在主线程外的单线程 executor 上运行。
- **BootReceiver** — 监听 `BOOT_COMPLETED`，检查 SharedPreferences 中是否有未完成的测试，有则启动 AutoStartService。
- **AutoStartService** — 前台服务（绕过 Android 10+ 后台启动 Activity 限制），3 秒后拉起 MainActivity，若 `SYSTEM_ALERT_WINDOW` 未授权则降级为全屏通知。
- **DeviceAdminReceiver** — 设备管理员接收器，用于 `DevicePolicyManager.reboot()`（模式 2 重启依赖）。

### 老化模式

- **模式 1（不断电循环）**: 持续运行所有勾选的测试项直至达到设定次数，每轮后保存状态到 SharedPreferences。断电后开机自动恢复。
- **模式 2（每轮重启）**: 每轮测试后尝试重启系统（依次尝试 DevicePolicyManager → su reboot → PowerManager API → reboot 命令），重启后 BootReceiver 触发自动恢复。所有降级失败则回到模式 1 继续。

### 测试项

5 项可选测试，勾选后在每轮中顺序执行：
1. 身份证读取（通过 Decard AAR 读卡器 SDK）
2. 非接触 CPU 卡（寻卡 → 复位 → 取随机数）
3. 接触 CPU 卡（复位 → 取随机数）
4. 以太网 PING（Java `InetAddress.isReachable()`）
5. 扫码测试（编辑框内容比对）

### 状态持久化

所有测试状态通过 `SharedPreferences` 保存（`f11aging_prefs`），支持断电/重启后恢复。日志文件写入 `getExternalFilesDir(null)/logs/`。

### 依赖

- 第三方 AAR: `libs/dc_reader_release_20260302133638.aar`（Decard 读卡器 SDK）
- SDK参考源码在 ref/BaseLibraryDemo
- Version catalog: `gradle/libs.versions.toml`
- AGP 9.1.1, Kotlin 2.1.0, minSdk 24, targetSdk 36, Java 11

### 关键注意事项

- 模式 2 重启需要 ADB 设置设备所有者：`adb shell dpm set-device-owner com.decard.f11aging/.DeviceAdminReceiver`
- 开机自启动需要用户授予 `SYSTEM_ALERT_WINDOW` 权限（Android 10+ 后台启动 Activity 需要）
- 读卡器操作通过 JNI (`BasicOper`) 调用，在非 UI 线程执行
- `getCheckedFlag()` 方法处理跨线程 CheckBox 状态读取（用 CountDownLatch 同步）
