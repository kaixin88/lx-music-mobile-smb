package cn.toside.music.mobile.smb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.toside.music.mobile.MainActivity

/**
 * SMB 备份前台服务：让备份在切到其他页面/其他应用后仍能继续运行，
 * 并通过通知展示进度。
 */
class SmbBackupService : Service() {

    companion object {
        private const val TAG = "SmbBackupService"
        const val CHANNEL_ID = "smb_backup_channel"
        const val NOTIFICATION_ID = 20261101

        const val ACTION_START = "cn.toside.music.mobile.smb.ACTION_START"
        const val ACTION_UPDATE = "cn.toside.music.mobile.smb.ACTION_UPDATE"
        const val ACTION_STOP = "cn.toside.music.mobile.smb.ACTION_STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_CURRENT = "extra_current"
        const val EXTRA_TOTAL = "extra_total"

        @Volatile
        var running: Boolean = false
            private set
    }

    private var titleText: String = "SMB 备份"
    private var contentText: String = "准备中"
    private var current = 0
    private var total = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopForegroundCompat()
                    stopSelf()
                    running = false
                    START_NOT_STICKY
                }
                ACTION_UPDATE -> {
                    readExtras(intent)
                    notifyProgress()
                    START_STICKY
                }
                else -> {
                    readExtras(intent)
                    startForeground(NOTIFICATION_ID, buildNotification())
                    running = true
                    START_STICKY
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handle command failed", t)
            running = false
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun readExtras(intent: Intent?) {
        titleText = intent?.getStringExtra(EXTRA_TITLE) ?: titleText
        contentText = intent?.getStringExtra(EXTRA_TEXT) ?: contentText
        current = intent?.getIntExtra(EXTRA_CURRENT, current) ?: current
        total = intent?.getIntExtra(EXTRA_TOTAL, total) ?: total
    }

    private fun notifyProgress() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stop foreground failed", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager?.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMB 备份",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "备份/恢复到 SMB 时的进度"
            setSound(null, null)
            enableVibration(false)
        }
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detail = if (total > 0) "$contentText  ($current/$total)" else contentText
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (total > 0) {
            builder.setProgress(total, current, false)
        }
        return builder.build()
    }
}
