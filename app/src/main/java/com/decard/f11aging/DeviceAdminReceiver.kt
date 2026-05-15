package com.decard.f11aging

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 设备管理员接收器
 * 用于支持 DevicePolicyManager.reboot()（模式2每轮重启需要）
 *
 * 首次使用需通过 ADB 设置设备所有者：
 *   adb shell dpm set-device-owner com.decard.f11aging/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}
