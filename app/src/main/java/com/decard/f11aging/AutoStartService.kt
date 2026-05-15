package com.decard.f11aging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * 开机自动启动前台服务
 *
 * Android 10+ 限制后台启动 Activity，此服务作为中间层：
 * 1. 以前台服务身份运行（系统优先级高，不易被杀）
 * 2. 等待系统启动完成（3秒）
 * 3. 若已授权 SYSTEM_ALERT_WINDOW，直接启动 Activity
 * 4. 否则降级为全屏通知（需用户点击）
 */
class AutoStartService : Service() {

    companion object {
        private const val CHANNEL_ID = "f11aging_autostart"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动前台服务（必须有通知）
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("F11老化工具")
            .setContentText("正在恢复老化测试...")
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // 后台线程等待后启动 Activity
        Thread {
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // 检查 SYSTEM_ALERT_WINDOW 权限（已授权则可直接启动 Activity）
            val hasOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)

            var launched = false

            if (hasOverlay) {
                // SYSTEM_ALERT_WINDOW 已授权，Android 10+ 允许后台启动 Activity
                try {
                    startActivity(launchIntent)
                    launched = true
                } catch (_: Exception) {}
            }

            // 未授权或直接启动失败，再尝试一次（部分设备仍允许）
            if (!launched) {
                try {
                    startActivity(launchIntent)
                    launched = true
                } catch (_: Exception) {}
            }

            // 最终降级：全屏通知（需用户点击）
            if (!launched) {
                postFullScreenNotification(launchIntent)
            }

            // 停止服务
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    /** 降级方案：全屏通知（需用户点击） */
    private fun postFullScreenNotification(intent: Intent) {
        // 重建高优先级渠道
        val channel = NotificationChannel(
            CHANNEL_ID, "自动启动", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "F11老化工具开机自启动"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("F11老化工具")
            .setContentText("点击恢复老化测试")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "自动启动", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
