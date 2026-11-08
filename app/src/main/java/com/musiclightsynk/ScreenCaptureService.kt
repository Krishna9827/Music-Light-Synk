package com.musiclightsynk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Optional fallback for apps that don't publish album art: grab ONE tiny (160 px wide) frame
 * of the screen on demand and read its four corners.
 *
 * The virtual display has no surface between captures, so nothing is rendered or copied while
 * idle - this is not a continuous HyperHDR-style grabber.
 */
class ScreenCaptureService : Service() {

    class Frame(val pixels: IntArray, val width: Int, val height: Int)

    companion object {
        private const val TAG = "MusicLightSynk"
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION_ID = 7
        private const val CAPTURE_WIDTH = 160
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0

    @Volatile
    private var armed = false

    @Volatile
    private var frame: IntArray? = null

    @Volatile
    private var frameTime = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Screen capture stopped by the system")
            handler.post {
                teardown()
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("mls-capture").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be in the foreground (type mediaProjection) *before* getMediaProjection on Android 10+.
        goForeground()
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            setup(code, data)
            instance = this
            Log.i(TAG, "Screen capture ready (${width}x$height)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start screen capture", e)
            teardown()
            stopSelf()
        }
        SyncEngine.notifyUi()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        teardown()
        thread.quitSafely()
        SyncEngine.notifyUi()
        super.onDestroy()
    }

    private fun setup(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("projection refused")
        // Android 14 requires the callback to be registered before createVirtualDisplay.
        p.registerCallback(projectionCallback, handler)
        projection = p

        val dm = DisplayMetrics()
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        val screenW = max(1, dm.widthPixels)
        val screenH = max(1, dm.heightPixels)
        width = CAPTURE_WIDTH
        height = max(2, (CAPTURE_WIDTH.toFloat() * screenH / screenW).roundToInt() and 1.inv())

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        // Start with no surface: nothing is rendered until capture() attaches the reader.
        virtualDisplay = p.createVirtualDisplay(
            "music-light-synk",
            width,
            height,
            max(1, dm.densityDpi),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            handler,
        )
    }

    /**
     * Blocking: attach the reader, wait for a settled frame, detach again. Call from a
     * background thread (the sync engine). Returns null if no frame arrived.
     */
    fun capture(timeoutMs: Long = 1000): Frame? {
        val vd = virtualDisplay ?: return null
        val rd = reader ?: return null
        val start = SystemClock.uptimeMillis()
        frame = null
        frameTime = 0L
        armed = true
        handler.post { setSurfaceSafely(vd, rd) }

        var result: IntArray? = null
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            SystemClock.sleep(40)
            // The first frame after re-attaching can be stale; prefer one at least ~120 ms in.
            if (frameTime != 0L && frameTime - start >= 120) {
                result = frame
                break
            }
        }
        if (result == null) result = frame
        armed = false
        handler.post { setSurfaceSafely(vd, null) }
        return result?.let { Frame(it, width, height) }
    }

    /** The service may be tearing down concurrently; a released display must not crash us. */
    private fun setSurfaceSafely(vd: VirtualDisplay, rd: ImageReader?) {
        if (virtualDisplay !== vd) return
        try {
            vd.surface = rd?.surface
        } catch (e: Exception) {
            Log.w(TAG, "setSurface failed", e)
        }
    }

    private fun onImage(r: ImageReader) {
        val img: Image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            if (armed) {
                frame = toPixels(img)
                frameTime = SystemClock.uptimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad frame", e)
        } finally {
            img.close()
        }
    }

    private fun toPixels(img: Image): IntArray {
        val w = minOf(img.width, width)
        val h = minOf(img.height, height)
        val plane = img.planes[0]
        val buf = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val out = IntArray(width * height)
        for (y in 0 until h) {
            var off = y * rowStride
            val row = y * width
            for (x in 0 until w) {
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                out[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                off += pixelStride
            }
        }
        return out
    }

    private fun teardown() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
                it.stop()
            } catch (_: Exception) {
            }
        }
        projection = null
        instance = null
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen colour sampling", NotificationManager.IMPORTANCE_MIN),
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Music Light Synk")
            .setContentText("Screen colour sampling is on")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}
