package com.musiclightsynk

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
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Backup listener (phones): keeps the microphone on in the background so this phone can drive
 * the WLED strips when music plays somewhere the app doesn't run (an iPhone, a PC on the
 * speakers, ...), and can send the beat to a TV whose own audio is silent (HDMI passthrough).
 *
 * A "microphone" foreground service. Android only lets it use the mic when it is started while
 * the app is on screen, so after a reboot open the app once.
 */
class ListenerService : Service() {

    override fun onCreate() {
        super.onCreate()
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // the "microphone" type exists from Android 11
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // Android 14+: not allowed from the background, or the mic permission is missing.
            Log.w(TAG, "Backup listener could not start", e)
            stopSelf()
            return
        }
        running = true
        SyncEngine.setListener(this, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (running) {
            running = false
            SyncEngine.setListener(this, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup listener", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("Listening for music")
            .setContentText("Music Light Synk drives your lights to the beat")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MusicLightSynk"
        private const val CHANNEL = "listener"
        private const val NOTIFICATION_ID = 21330

        @Volatile
        var running = false
            private set

        /** Phones only: a microphone is needed. */
        fun isSupported(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        fun hasMicPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Call while the app is on screen. Returns false if it can't run here. */
        fun start(ctx: Context): Boolean {
            if (!isSupported(ctx) || !hasMicPermission(ctx)) return false
            return try {
                val i = Intent(ctx, ListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the backup listener", e)
                false
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenerService::class.java))
        }
    }
}
