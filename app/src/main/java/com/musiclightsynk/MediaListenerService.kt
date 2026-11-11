package com.musiclightsynk

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * We don't care about notifications at all. Android only lets *notification listeners* read
 * other apps' media sessions (title, artist, album art), so this service exists to get that
 * access. Bonus: the system keeps a granted listener bound permanently, so Fire OS doesn't
 * kill us in the background.
 *
 * Grant it once over ADB (Fire TV hides the settings screen):
 *   adb shell cmd notification allow_listener com.musiclightsynk/com.musiclightsynk.MediaListenerService
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicLightSynk"
        private const val DEBOUNCE_MS = 600L

        @Volatile
        var connected = false
            private set

        private val ACTIVE_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        )

        fun isActive(state: PlaybackState?): Boolean = state != null && state.state in ACTIVE_STATES

        fun component(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

        /** Is notification-listener access granted (regardless of whether we're bound yet)? */
        fun isEnabled(ctx: Context): Boolean {
            val cn = component(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm != null) return nm.isNotificationListenerAccessGranted(cn)
            }
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it) == cn }
        }

        fun requestRebindIfNeeded(ctx: Context) {
            if (connected || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isEnabled(ctx)) return
            try {
                requestRebind(component(ctx))
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind failed", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var tracked: List<Tracked> = emptyList()
    private val labelCache = HashMap<String, String>()
    private val publishTask = Runnable { publish() }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessionsChanged(list) }

    /** One per media session; caches metadata so we don't re-fetch the artwork over IPC. */
    private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
        var metadata: MediaMetadata? = controller.metadata
        var state: PlaybackState? = controller.playbackState

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            this.metadata = metadata
            schedulePublish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val wasActive = isActive(this.state)
            this.state = state
            // Position ticks arrive constantly; only playing <-> paused matters to us.
            if (isActive(state) != wasActive) schedulePublish()
        }

        override fun onSessionDestroyed() {
            state = null
            schedulePublish()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component(this), main)
            onSessionsChanged(msm.getActiveSessions(component(this)))
        } catch (e: SecurityException) {
            Log.e(TAG, "Media session access refused", e)
        }
        Log.i(TAG, "Listener connected")
        SyncEngine.notifyUi()
        SyncEngine.reportStatus(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        teardown()
        SyncEngine.notifyUi()
        SyncEngine.reportStatus(this)
        requestRebindIfNeeded(this)
    }

    override fun onDestroy() {
        connected = false
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        tracked.forEach { it.controller.unregisterCallback(it) }
        tracked = emptyList()
        main.removeCallbacks(publishTask)
    }

    private fun onSessionsChanged(list: List<MediaController>?) {
        tracked.forEach { it.controller.unregisterCallback(it) }
        tracked = (list ?: emptyList())
            .filter { it.packageName != packageName }
            .map { c -> Tracked(c).also { c.registerCallback(it, main) } }
        schedulePublish()
    }

    private fun schedulePublish() {
        main.removeCallbacks(publishTask)
        main.postDelayed(publishTask, DEBOUNCE_MS)
    }

    private fun publish() {
        val prefs = Prefs(this)
        val allowed = prefs.allowedPackages
        // Apps whose per-app profile is "off" are invisible to us: e.g. ignore Netflix entirely.
        val candidates = tracked.filter {
            val pkg = it.controller.packageName
            (allowed.isEmpty() || pkg in allowed) && prefs.profileFor(pkg) != AppProfile.OFF
        }
        // getActiveSessions() is already ordered by priority; prefer whatever is actually playing.
        val chosen = candidates.firstOrNull { isActive(it.state) } ?: candidates.firstOrNull()
        SyncEngine.onNowPlaying(this, chosen?.let { toNowPlaying(it) })
    }

    private fun toNowPlaying(t: Tracked): NowPlaying {
        val md = t.metadata
        val pkg = t.controller.packageName
        return NowPlaying(
            packageName = pkg,
            appLabel = appLabel(pkg),
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            playing = isActive(t.state),
            art = Artwork.bitmapFrom(md),
            artUri = Artwork.uriFrom(md),
        )
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
