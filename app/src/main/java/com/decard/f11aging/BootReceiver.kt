package com.decard.f11aging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启动广播接收器
 * 用于模式1（断电恢复）和模式2（每轮重启）的开机自动恢复
 *
 * 启动 AutoStartService 前台服务来拉起 Activity（Android 10+ 后台启动限制）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        // 检查是否有未完成的自动测试
        val prefs = context.getSharedPreferences("f11aging_prefs", Context.MODE_PRIVATE)
        val autoMode = prefs.getBoolean("auto_mode_enabled", false)
        if (!autoMode) return

        // 启动前台服务来拉起 Activity（比直接 startActivity 更可靠）
        val serviceIntent = Intent(context, AutoStartService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
