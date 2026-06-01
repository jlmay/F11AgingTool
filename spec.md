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

两种模式均支持启动延时。通过 UI 中的"启动延时"输入框设定（单位：毫秒），用于系统启动后延迟指定时间再开始测试。默认值为 1000ms（1 秒）。

## 3. 测试项

5 项可选测试，勾选后在每轮中顺序执行。支持任意组合勾选。**默认勾选：① 身份证读取、④ 以太网 PING。**

| # | 测试项 | 默认 | 成功条件 | 实现方式 |
|---|--------|------|----------|----------|
| ① | 身份证信息读取 | ✅ | 通过德卡 Android SDK 读出身份证姓名和号码 | `BasicOper.dc_IdCardReadCardInfo()` |
| ② | 非接触 CPU 卡 | ❌ | 寻卡 → 复位 → 取随机数，三项均成功 | `dc_card_hex()` → `dc_pro_resethex()` → `dc_procommandInt_hex("0084000008")` |
| ③ | 接触 CPU 卡 | ❌ | 复位 → 取随机数，两项均成功 | `dc_cpureset_hex()` → `dc_cpuapduInt_hex("0084000008")` |
| ④ | 以太网 PING | ✅ | 所有 PING 请求均成功（默认 10 次，可配置） | `InetAddress.isReachable()`，默认目标 `www.baidu.com`（可修改） |
| ⑤ | 扫码测试 | ❌ | 有扫码数据且与样本二维码一致 | 编辑框内容比对 |

## 4. 界面要求

- 包括测试记录（时间、成功/失败次数），日志保存为文件，可按需查找；
- Android 屏幕为 8 寸竖屏，分辨率 800×1280，界面需适配此分辨率；
- 界面大方、显示清晰明了。

### 界面布局

页面采用上下结构（标题栏 + 主内容滚动区 + 统计栏 + 网络信息栏 + 实时日志区）：

- **标题栏**：应用名称 + 运行状态（空闲/测试中/已暂停）
- **主内容区**（从上到下，可滚动）：
  - 老化模式选择（RadioGroup，2 种模式）
  - 参数设置（测试总次数、启动延时、PING 地址、PING 次数）
  - 测试项目勾选（5 项 CheckBox，可多选）
  - 扫码样本输入区（勾选扫码测试时显示）
  - 控制按钮（开始/暂停/停止测试、查看历史日志）
- **固定统计栏**（浅蓝色背景，始终可见，不随内容滚动）：
  - 已测次数（蓝色 22sp）、总计次数（深色 22sp）
  - 成功次数（绿色 22sp）、失败次数（红色 22sp）
- **网络信息栏**：显示设备当前 IP 地址（测试中可见）
- **实时日志区**：深色背景，等宽字体，滚动显示最近 10 轮日志

### 按钮状态

`btnStart` 为三态按钮：

| 状态 | 按钮文字 | 颜色 | 说明 |
|------|----------|------|------|
| 空闲 | 开始测试 | 蓝色 | 启动新一轮老化测试 |
| 测试中 | 暂停测试 | 蓝色 | 暂停当前测试，可查看实时状态 |
| 暂停中 | 继续测试 | 琥珀色 | 恢复被暂停的测试 |

`btnStop`（停止测试，红色）在任何状态下均可点击，立即终止测试并清除自动恢复标志。

## 5. 日志系统

### 日志文件（历史日志）

日志文件写入 `getExternalFilesDir(null)/logs/` 目录，文件名格式 `aging_yyyyMMdd_HHmmss.txt`，同一测试项目重启后日志继续写入同一文件。

**记录以下内容：**

- 测试配置（会话开始标记、总次数、模式、延时、测试项清单）
- **测试失败记录** — 出现失败时，记录失败原因、测试项名称、时间戳，例如：
  ```
  [2026-06-01 09:00:05]  取序列号失败: xxxx
  [2026-06-01 09:00:05] 身份证: ✗ 失败
  [2026-06-01 09:00:10] 以太网 PING 超时
  [2026-06-01 09:00:10] 以太网PING: ✗ 失败
  ```
- `====== 测试已手动停止 ======` — 手动停止标记
- `====== 全部测试完成 ======` — 会话结束标记
- `成功: X  失败: Y  总计: Z` — 最终统计
- 模式 2 重启失败/降级相关信息
- 开机自动恢复相关日志

**不记录的内容：** 测试成功时的详细调试信息（序列号、ATR、随机数等），以及轮次正常进行的实时日志。

### 实时日志（屏幕显示）

实时日志区展示最近 10 轮的详细日志，包括每项测试的内部调试信息（序列号、ATR、随机数等），用于实时监控测试进度和排查问题。不写入历史文件。

### 日志方法

| 方法 | 写文件 | 写屏幕 | 用途 |
|------|--------|--------|------|
| `appendLog(msg)` | ✅ | ✅ | 测试配置、最终统计、**失败原因**、关键事件 |
| `appendLogScreen(msg)` | ❌ | ✅ | 调试详情、成功信息、设备操作 |

## 6. 暂停/继续功能

测试过程中可随时暂停（点击"暂停测试"），查看当前测试统计和实时日志状态，之后点击"继续测试"恢复。

### 实现机制

- `AtomicBoolean isPaused` 标记暂停状态
- `Object pauseLock` 配合 `wait()/notifyAll()` 模式阻塞 executor 线程，避免忙等
- `checkPause()` 方法在以下位置检查暂停标志：
  - `runTestLoop()` 每轮迭代开始时
  - `runOnce()` 每个测试项执行完毕后
  - `testEthernet()` PING 循环内部（PING 耗时最长，需响应快速暂停）
  - `runSingleRoundAndReboot()` 开始处

### 边界处理

- 暂停状态**不持久化**到 SharedPreferences（重启后按原有恢复逻辑正常工作）
- 停止测试时自动清除暂停标志并唤醒阻塞线程
- Activity 销毁时同样清理暂停锁

## 7. 状态持久化

所有测试状态通过 `SharedPreferences`（名称 `f11aging_prefs`）保存，支持断电/重启后恢复。

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

## 8. 核心架构

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

## 9. 开发与编译

### 编译环境

- JDK 17（Temurin），路径 `D:/jdk-17.0.14.7-hotspot`
- `gradle.properties` 中 `org.gradle.java.home` 已锁定此路径（正斜杠格式）
- Android SDK 通过 `local.properties` 或 `ANDROID_HOME` 环境变量指定

### 一键编译脚本

```bash
./build.sh          # 快速编译 assembleDebug
./build.sh clean    # 清理后全量编译
./build.sh install  # 编译并安装到已连接设备
```