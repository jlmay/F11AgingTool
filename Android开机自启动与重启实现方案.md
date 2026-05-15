# Android 应用开机自启动与重启实现方案

> 适用环境：Android 10+（实测 Android 11），无 root，非系统签名 APK

---

## 一、开机自动运行

### 1.1 问题背景

Android 10+ 限制后台启动 Activity，`BroadcastReceiver.onReceive()` 中直接调用 `startActivity()` 会被系统静默拦截，应用无法在开机后自动运行。

### 1.2 解决方案：前台服务 + SYSTEM_ALERT_WINDOW 权限

**核心思路**：`SYSTEM_ALERT_WINDOW`（显示在其他应用上层）权限可豁免 Android 10+ 的后台启动限制，授权后即可直接 `startActivity()`。

**启动链路**：

```
开机 → BOOT_COMPLETED 广播
     → BootReceiver.onReceive()
     → 启动 AutoStartService（前台服务）
     → 等待 3 秒系统就绪
     → 检查 SYSTEM_ALERT_WINDOW 权限
        ├─ 已授权 → 直接 startActivity() ✓
        ├─ 未授权 → 尝试 startActivity()（部分设备仍允许）
        └─ 均失败 → 降级为全屏通知（需用户点击）
```

### 1.3 涉及文件及关键代码

#### BootReceiver.kt — 开机广播接收器

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 验证广播 Action
        if (action != ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON) return

        // 检查是否有未完成的自动测试
        val prefs = context.getSharedPreferences("f11aging_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_mode_enabled", false)) return

        // 启动前台服务（Android 8.0+ 用 startForegroundService）
        val serviceIntent = Intent(context, AutoStartService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(serviceIntent)
        else
            context.startService(serviceIntent)
    }
}
```

#### AutoStartService.kt — 前台服务（核心）

```kotlin
class AutoStartService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. 启动前台通知（必须，否则服务会被杀）
        startForeground(NOTIFICATION_ID, buildNotification())

        // 2. 后台线程等待后启动 Activity
        Thread {
            Thread.sleep(3000) // 等待系统就绪

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
            }

            // 3. 检查 SYSTEM_ALERT_WINDOW 权限
            val hasOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)

            var launched = false
            if (hasOverlay) {
                try { startActivity(launchIntent); launched = true } catch (_: Exception) {}
            }
            if (!launched) {
                try { startActivity(launchIntent); launched = true } catch (_: Exception) {}
            }
            if (!launched) {
                postFullScreenNotification(launchIntent) // 降级：全屏通知
            }
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }
}
```

#### AndroidManifest.xml — 权限声明

```xml
<!-- 开机广播 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- 前台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<!-- 显示在其他应用上层（Android 10+ 后台启动 Activity 的关键） -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<!-- 全屏通知（降级方案） -->
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

<receiver android:name=".BootReceiver" android:exported="true" android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>

<service android:name=".AutoStartService" android:exported="false" android:enabled="true" />
```

### 1.4 用户操作（一次性）

1. 安装 APP 后首次点击「开始测试」→ 弹窗提示开启「显示在其他应用上层」权限
2. 点"去设置" → 打开开关
3. **授权后永久生效**，后续开机不再需要任何手动操作

### 1.5 注意事项

- **部分国产设备**（小米、华为等）需在系统设置中额外开启「自启动管理」
- **电池优化**建议关闭（设置 → 应用 → 电池 → 不受限制），防止系统杀后台
- `QUICKBOOT_POWERON` 支持 HTC 等设备的快速开机广播
- 前台服务必须有通知，否则 Android 8.0+ 会抛异常

---

## 二、应用内重启设备

### 2.1 问题背景

`PowerManager.reboot()` 需要 `REBOOT` 权限，该权限仅系统签名 APK 可获得。普通应用调用会抛出 `SecurityException`。

### 2.2 解决方案：DevicePolicyManager 设备所有者

**核心思路**：通过 ADB 将 APP 设为「设备所有者」，即可调用 `DevicePolicyManager.reboot()` 无需 root。

**重启策略降级顺序**：

```
1. DevicePolicyManager.reboot()  ← 设备所有者权限（推荐，本次采用）
2. su -c reboot                  ← 需要 root
3. PowerManager.reboot()         ← 需要系统签名 APK
4. reboot shell 命令             ← 需要特权
5. 降级为不断电循环               ← 以上均失败
```

### 2.3 涉及文件及关键代码

#### DeviceAdminReceiver.kt — 设备管理员接收器

```kotlin
class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}
```

#### res/xml/device_admin_policies.xml — 设备管理策略

```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

#### MainActivity.kt — 重启逻辑

```kotlin
private fun rebootDevice() {
    // 方式1：DevicePolicyManager（设备所有者）
    if (deviceOwnerReboot()) return
    // 方式2：shell su reboot（root）
    if (shellReboot()) return
    // 方式3：PowerManager（系统签名）
    try { pm.reboot(null); return } catch (_: Exception) {}
    // 方式4：reboot 命令
    try { Runtime.getRuntime().exec(arrayOf("reboot")); return } catch (_: Exception) {}
    // 降级：继续循环不重启
    continueTestLoop(remaining, total, runMode = 1)
}

private fun deviceOwnerReboot(): Boolean {
    return try {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.reboot(ComponentName(this, DeviceAdminReceiver::class.java))
        true
    } catch (_: Exception) { false }
}
```

#### AndroidManifest.xml — 设备管理员声明

```xml
<receiver
    android:name=".DeviceAdminReceiver"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN">
    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin_policies" />
    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
    </intent-filter>
</receiver>
```

### 2.4 设备端设置（一次性）

通过 ADB 将应用设为设备所有者：

```bash
adb shell dpm set-device-owner com.decard.f11aging/.DeviceAdminReceiver
```

**成功输出**：
```
Success: Device owner set to package ComponentInfo{com.decard.f11aging/com.decard.f11aging.DeviceAdminReceiver}
Active admin set to component {com.decard.f11aging/com.decard.f11aging.DeviceAdminReceiver}
```

### 2.5 注意事项

- `dpm set-device-owner` 要求设备上**没有已登录的 Google/其他账号**，否则报错
- 设置后应用获得设备所有者权限，可调用 `DevicePolicyManager` 全部 API
- **卸载前需先移除设备所有者**：`adb shell dpm remove-active-admin com.decard.f11aging/.DeviceAdminReceiver`
- 设备恢复出厂设置也会清除设备所有者

---

## 三、完整流程总结

### 模式1：不断电循环（断电自动恢复）

```
开始测试 → saveState() → 循环测试
                              ↓ 每轮后 saveState()
                              ↓ 保存 remaining/currentCount/successCount/failCount
                              ↓
                        断电了！
                              ↓
                        重新开机
                              ↓
                   BootReceiver 检测 auto_mode_enabled=true
                              ↓
                   AutoStartService → 启动 MainActivity
                              ↓
                   checkAutoStart() → 读取 SharedPreferences
                              ↓
                   resumeTesting() → 从断点继续循环
```

### 模式2：每轮重启

```
开始测试 → saveState() → 测试一轮
                              ↓
                   saveState(remaining - 1)
                              ↓
                   DevicePolicyManager.reboot()
                              ↓
                        系统重启
                              ↓
                   BootReceiver → AutoStartService → 启动 APP
                              ↓
                   checkAutoStart() → remaining > 0
                              ↓
                   测试下一轮 → saveState → reboot → 重复
                              ↓
                   remaining = 0 → 测试完成 → clearAutoState()
```

### SharedPreferences 状态键

| 键名 | 类型 | 用途 |
|------|------|------|
| auto_mode_enabled | Boolean | 是否有未完成的自动测试 |
| run_mode | Int | 1=不断电, 2=每轮重启 |
| auto_mode_remaining | Int | 剩余测试次数 |
| auto_mode_total | Int | 总测试次数 |
| auto_mode_current | Int | 已完成次数 |
| auto_mode_success | Int | 成功次数 |
| auto_mode_fail | Int | 失败次数 |
| auto_mode_delay | Int | 启动延时(ms) |
| auto_cb_idcard | Boolean | 身份证测试项 |
| auto_cb_contactless | Boolean | 非接触CPU卡测试项 |
| auto_cb_contact | Boolean | 接触CPU卡测试项 |
| auto_cb_ethernet | Boolean | 以太网PING测试项 |
| auto_cb_barcode | Boolean | 扫码测试项 |
| auto_ping_host | String | PING 目标地址 |
| auto_ping_count | String | PING 次数 |
| auto_barcode_ref | String | 样本二维码 |

---

## 四、设备部署清单

安装 APP 后需完成以下一次性设置：

| 步骤 | 操作 | 命令/路径 |
|------|------|-----------|
| 1 | 安装 APK | `adb install -r app-debug.apk` |
| 2 | 设置设备所有者 | `adb shell dpm set-device-owner com.decard.f11aging/.DeviceAdminReceiver` |
| 3 | 开启「显示在其他应用上层」权限 | 设置 → 应用 → F11老化工具 → 显示在其他应用上层 → 开启 |
| 4 | 关闭电池优化（可选） | 设置 → 应用 → 电池 → 不受限制 |
| 5 | 开启自启动管理（部分国产设备） | 设置 → 自启动管理 → 允许 F11老化工具 |
