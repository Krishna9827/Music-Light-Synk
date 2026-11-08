package com.musiclightsynk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** What the active media session is playing right now. */
data class NowPlaying(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val playing: Boolean,
    val art: Bitmap?,
    val artUri: String?,
) {
    val trackKey: String get() = "$packageName|$title|$artist|$album"

    fun describe(): String {
        val track = listOfNotNull(title?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() })
            .joinToString(" - ")
            .ifEmpty { "(no track info)" }
        val state = if (playing) "playing" else "paused"
        return "$appLabel: $track ($state)"
    }
}

/** Pulls album art out of MediaMetadata (bitmap or URI) and turns it into a [Palette]. */
object Artwork {
    private const val TAG = "MusicLightSynk"
    private const val SAMPLE_SIDE = 64
    private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024

    private val BITMAP_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    )
    private val URI_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    )

    fun bitmapFrom(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        for (key in BITMAP_KEYS) {
            val bmp = try {
                md.getBitmap(key)
            } catch (e: Exception) {
                null
            }
            if (bmp != null && !bmp.isRecycled && bmp.width > 1 && bmp.height > 1) return bmp
        }
        return null
    }

    fun uriFrom(md: MediaMetadata?): String? {
        if (md == null) return null
        for (key in URI_KEYS) {
            val s = md.getString(key)
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    fun palette(bmp: Bitmap, opts: ExtractOptions): Palette? {
        val sample = sample(bmp, SAMPLE_SIDE) ?: return null
        return ColorExtractor.extract(sample.pixels, sample.width, sample.height, opts)
    }

    class Sample(val pixels: IntArray, val width: Int, val height: Int)

    /** Downscale (box-filter style, by repeated halving) and read the pixels. */
    fun sample(bmp: Bitmap, maxSide: Int): Sample? {
        return try {
            var src = bmp
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
                src = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            var cur = src
            while (max(cur.width, cur.height) / 2 >= maxSide) {
                val next = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
                if (cur !== bmp && cur !== next) cur.recycle()
                cur = next
            }
            val scale = maxSide.toFloat() / max(cur.width, cur.height)
            if (scale < 1f) {
                val w = max(1, (cur.width * scale).roundToInt())
                val h = max(1, (cur.height * scale).roundToInt())
                val next = Bitmap.createScaledBitmap(cur, w, h, true)
                if (cur !== bmp && cur !== next) cur.recycle()
                cur = next
            }
            val w = cur.width
            val h = cur.height
            val px = IntArray(w * h)
            cur.getPixels(px, 0, w, 0, 0, w, h)
            if (cur !== bmp) cur.recycle()
            Sample(px, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read artwork bitmap", e)
            null
        }
    }

    /** Load artwork from a content://, file://, android.resource:// or http(s):// URI. */
    fun load(ctx: Context, uri: String): Bitmap? {
        return try {
            val u = Uri.parse(uri)
            val bytes = when (u.scheme?.lowercase(Locale.US)) {
                "http", "https" -> download(uri)
                "content", "file", "android.resource" ->
                    ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
                else -> null
            } ?: return null
            decodeSampled(bytes, 256)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load artwork from $uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Artwork too large: $uri")
            null
        }
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD_BYTES) return null
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= target) sampleSize *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
