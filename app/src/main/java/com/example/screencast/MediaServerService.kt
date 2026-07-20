package com.example.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps [LocalMediaServer] alive while a receiver streams from the phone.
 *
 * The receiver pulls the file for as long as it plays, so the server has to outlive the activity —
 * otherwise locking the screen would cut the stream. Running in the foreground also keeps Wi-Fi and
 * the CPU awake, which the system would otherwise throttle once the screen turns off.
 */
class MediaServerService : Service() {

    inner class LocalBinder : Binder() {
        val service: MediaServerService get() = this@MediaServerService
    }

    private val binder = LocalBinder()
    private lateinit var server: LocalMediaServer
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        server = LocalMediaServer(contentResolver)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Publishes [uri] and returns the URL a receiver on the same Wi-Fi can fetch, or null. */
    fun publish(uri: Uri): String? = server.publish(uri)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_TITLE)))
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        server.close()
        super.onDestroy()
    }

    private fun buildNotification(title: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.serving_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_notification)
            .setContentTitle(getString(R.string.serving_notification_title))
            .setContentText(title ?: getString(R.string.media_title))
            .setContentIntent(open)
            .addAction(0, getString(R.string.serving_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireLocks() {
        if (wifiLock != null) return

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager
            ?.createWifiLock(mode, WIFI_LOCK_TAG)
            ?.apply { acquire() }

        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.example.screencast.STOP_SERVING"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "media_server"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_LOCK_TAG = "ScreenCast:wifi"
        private const val WAKE_LOCK_TAG = "ScreenCast:server"

        /** Long enough for a feature film; the lock is released when serving stops anyway. */
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        fun startIntent(context: Context, title: String?): Intent =
            Intent(context, MediaServerService::class.java).putExtra(EXTRA_TITLE, title)
    }
}
