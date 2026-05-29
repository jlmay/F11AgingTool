# F11-N 设备底座老化测试工具 — 需求与方案

## 1. 设备概述

针对 F11-N 设备开发一个 Android 老化测试程序。F11-N 设备由 Android 平板和底座组成，底座通过 USB 接入 Android 平板。该程序用于对 F11-N 设备底座进行老化测试，以验证 Android 平板和底座设备之间的稳定性。

### 底座待测试功能

- **身份证、非接触 CPU 卡、接触 CPU 卡**：通过调用德卡读卡器 Android 开发库 `dc_reader_release_20260302133638.aar` 实现；
- **底座扫码功能**：扫码头通过 HID 模拟键盘接入 Android 平板，扫码后数据自动上传到 Android 平板当前光标处；
- **以太网功能**：底座上有一个 USB 转以太网芯片，该芯片接入 Android 平板后为平板提供以太网通讯功能。

## 2. 老化模式

程序提供两种老化模式，均支持断电/重启后自动恢复测试（通过 `BootReceiver` + `AutoStartService` + `SharedPreferences` 实现）。

### 模式 1：不断电循环

持续运行所有勾选的测试项直至达到设定次数。每轮完成后保存状态到 `SharedPreferences`，断电后开机自动恢复继续测试。

### 模式 2：每轮重启

每轮测试后尝试重启系统（依次尝试 `DevicePolicyManager.reboot()` → `su reboot` → `PowerManager.reboot()` → `reboot` 命令）。重启后 `BootReceiver` 触发自动恢复，继续下一轮测试。所有重启方式均失败时降级为模式 1 继续循环。

### 启动延时

两种模式均支持启动延时。通过 UI 中的"启动延时"输入框设定（单位：毫秒），用于系统启动后延迟指定时间再开始测试。默认值为 0，表示不延时。

## 3. 测试项

5 项可选测试，勾选后在每轮中顺序执行。支持任意组合勾选。

| # | 测试项 | 成功条件 | 实现方式 |
|---|--------|----------|----------|
| ① | 身份证信息读取 | 通过德卡 Android SDK 读出身份证姓名和号码 | `BasicOper.dc_IdCardReadCardInfo()` |
| ② | 非接触 CPU 卡 | 寻卡 → 复位 → 取随机数，三项均成功 | `dc_card_hex()` → `dc_pro_resethex()` → `dc_procommandInt_hex("0084000008")` |
| ③ | 接触 CPU 卡 | 复位 → 取随机数，两项均成功 | `dc_cpureset_hex()` → `dc_cpuapduInt_hex("0084000008")` |
| ④ | 以太网 PING | 所有 PING 请求均成功（默认 10 次，可配置） | `InetAddress.isReachable()`，默认目标 `www.baidu.com`（可修改） |
| ⑤ | 扫码测试 | 有扫码数据且与样本二维码一致 | 编辑框内容比对 |

## 4. 界面要求

- 包括测试记录（时间、成功/失败次数），日志保存为文件，可按需查找；
- Android 屏幕为 8 寸竖屏，分辨率 800×1280，界面需适配此分辨率；
- 界面大方、显示清晰明了。

### 界面布局

页面采用上下结构（标题栏 + 主内容滚动区 + 网络信息栏 + 实时日志区）：

- **标题栏**：应用名称 + 运行状态（空闲/测试中）
- **主内容区**（从上到下）：
  - 老化模式选择（RadioGroup，2 种模式）
  - 参数设置（测试总次数、启动延时、PING 地址、PING 次数）
  - 测试项目勾选（5 项 CheckBox，可多选）
  - 扫码样本输入区（勾选扫码测试时显示）
  - 测试统计（已测/总计/成功/失败）
  - 控制按钮（开始/停止测试、查看历史日志）
- **网络信息栏**：显示设备当前 IP 地址（测试中可见）
- **实时日志区**：深色背景，等宽字体，滚动显示最近 10 轮日志

## 5. 状态持久化

所有测试状态通过 `SharedPreferences`（名称 `f11aging_prefs`）保存，支持断电/重启后恢复。日志文件写入 `getExternalFilesDir(null)/logs/` 目录，同一测试项目重启后日志继续写入同一文件。

### 持久化键值

| 键 | 类型 | 说明 |
|----|------|------|
| `auto_mode_enabled` | Boolean | 是否有未完成的自动测试 |
| `run_mode` | Int | 运行模式（1=不断电, 2=每轮重启） |
| `auto_mode_remaining` | Int | 剩余测试次数 |
| `auto_mode_total` | Int | 总测试次数 |
| `auto_mode_current` | Int | 已测次数 |
| `auto_mode_success` | Int | 成功次数 |
| `auto_mode_fail` | Int | 失败次数 |
| `auto_mode_delay` | Int | 启动延时（毫秒） |
| `auto_ping_host` | String | PING 目标地址 |
| `auto_ping_count` | String | PING 次数 |
| `auto_cb_idcard` | Boolean | 勾选身份证测试 |
| `auto_cb_contactless` | Boolean | 勾选非接触 CPU 卡测试 |
| `auto_cb_contact` | Boolean | 勾选接触 CPU 卡测试 |
| `auto_cb_ethernet` | Boolean | 勾选以太网测试 |
| `auto_cb_barcode` | Boolean | 勾选扫码测试 |
| `auto_barcode_ref` | String | 样本二维码值 |
| `auto_log_file` | String | 当前日志文件绝对路径 |

## 6. 核心架构

| 组件 | 职责 |
|------|------|
| `MainActivity` | 唯一 Activity，管理全部测试逻辑和 UI。测试循环在主线程外的单线程 executor 上运行 |
| `BootReceiver` | 监听 `BOOT_COMPLETED`，检查 SharedPreferences 中是否有未完成的测试，有则启动 `AutoStartService` |
| `AutoStartService` | 前台服务（绕过 Android 10+ 后台启动 Activity 限制），3 秒后拉起 `MainActivity`，若 `SYSTEM_ALERT_WINDOW` 未授权则降级为全屏通知 |
| `DeviceAdminReceiver` | 设备管理员接收器，用于 `DevicePolicyManager.reboot()`（模式 2 重启依赖） |

### 关键依赖

- 德卡读卡器 SDK：`libs/dc_reader_release_20260302133638.aar`
- AGP 9.1.1, Kotlin 2.1.0, minSdk 24, targetSdk 36, Java 11

### 注意事项

- 模式 2 重启需要 ADB 设置设备所有者：`adb shell dpm set-device-owner com.decard.f11aging/.DeviceAdminReceiver`
- 开机自启动需要用户授予 `SYSTEM_ALERT_WINDOW` 权限（Android 10+ 后台启动 Activity 需要）
- 读卡器操作通过 JNI（`BasicOper`）调用，在非 UI 线程执行
- `getCheckedFlag()` 方法处理跨线程 CheckBox 状态读取（用 `CountDownLatch` 同步）
