package com.decard.f11aging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动广播接收器
 * 用于老化模式3：系统上电后自动启动测试程序
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        // 检查是否有自动模式标志
        val prefs = context.getSharedPreferences("f11aging_prefs", Context.MODE_PRIVATE)
        val autoMode = prefs.getBoolean("auto_mode_enabled", false)

        // 无论是否是自动模式，都在开机时自动启动主界面
        // 主界面会自行判断是否需要执行自动测试
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
    }
}
