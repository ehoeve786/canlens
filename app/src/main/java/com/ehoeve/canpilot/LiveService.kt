package com.ehoeve.canpilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps a live stream and its recording going with the app in the background or the screen off: a foreground
 * service with a notification that shows how far it got and can stop it, and a wake lock so the CPU keeps reading.
 * The stream itself stays in [LiveState]; this only keeps the process awake and visible.
 */
class LiveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val refresh = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(ID, notification())
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop?.invoke(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Live stream", NotificationManager.IMPORTANCE_LOW))
        // location too when the phone's GPS may be read: Android stops its updates in the background otherwise
        val gps = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "canpilot:live").apply { acquire() }
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 2000)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title?.invoke() ?: "Live stream")
            .setContentText(status?.invoke() ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", end).build())
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "live"
        private const val ID = 1
        private const val ACTION_STOP = "stop"
        /** Set by [LiveState] while a stream runs: the notification's two lines, and what its Stop button does. */
        @Volatile var title: (() -> String)? = null
        @Volatile var status: (() -> String)? = null
        @Volatile var stop: (() -> Unit)? = null

        fun begin(context: Context) { context.startForegroundService(Intent(context, LiveService::class.java)) }
        fun end(context: Context) { context.stopService(Intent(context, LiveService::class.java)) }
    }
}
