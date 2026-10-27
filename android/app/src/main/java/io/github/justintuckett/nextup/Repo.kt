package io.github.justintuckett.nextup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads episodes.json from your GitHub Pages site and caches it (plus posters) on the phone. */
object Repo {
    private const val FEED_FILE = "episodes.json"
    private const val PREFS = "nextup"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dataUrl(ctx: Context): String =
        prefs(ctx).getString("dataUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DATA_URL

    fun setDataUrl(ctx: Context, url: String?) {
        prefs(ctx).edit().apply {
            if (url.isNullOrBlank() || url.trim() == BuildConfig.DATA_URL) remove("dataUrl") else putString("dataUrl", url.trim())
        }.apply()
    }

    fun hideWatched(ctx: Context) = prefs(ctx).getBoolean("hideWatched", false)
    fun setHideWatched(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("hideWatched", v).apply()

    fun lastFetch(ctx: Context) = prefs(ctx).getLong("lastFetch", 0L)

    fun load(ctx: Context): Feed? = runCatching {
        val f = File(ctx.filesDir, FEED_FILE)
        if (f.exists()) parseFeed(f.readText()) else null
    }.getOrNull()

    suspend fun refresh(ctx: Context): Result<Feed> = withContext(Dispatchers.IO) {
        runCatching {
            // The minute-based suffix skips stale caches between GitHub Pages and the phone.
            val text = httpGet(dataUrl(ctx) + "?v=" + System.currentTimeMillis() / 60000)
            val feed = parseFeed(text)
            val tmp = File(ctx.filesDir, "$FEED_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(ctx.filesDir, FEED_FILE))) throw IOException("Couldn't save the episode list")
            prefs(ctx).edit().putLong("lastFetch", System.currentTimeMillis()).apply()
            cachePosters(ctx, feed)
            feed
        }
    }

    /** Returns the download link if GitHub has a newer build than this one. */
    suspend fun newerBuildUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(httpGet("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest"))
            val build = o.getString("tag_name").removePrefix("build-").toInt()
            if (build > BuildConfig.VERSION_CODE)
                "https://github.com/${BuildConfig.REPO}/releases/latest/download/next-up.apk"
            else null
        }.getOrNull()
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "NextUp-Android")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---------- posters ----------

    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun posterFile(ctx: Context, showId: Int): File =
        File(File(ctx.filesDir, "posters").apply { mkdirs() }, "$showId.jpg")

    private fun cachePosters(ctx: Context, feed: Feed) {
        for (s in feed.shows.values) {
            if (s.poster.isBlank()) continue
            val f = posterFile(ctx, s.id)
            if (f.exists() && f.length() > 0) continue
            runCatching {
                val c = URL(s.poster).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                val w = 240
                val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
                val tmp = File(f.path + ".tmp")
                FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                tmp.renameTo(f)
            }
        }
    }

    /** Poster scaled to [width] px. RGB_565 keeps widget memory small. */
    fun poster(ctx: Context, showId: Int, width: Int): Bitmap? {
        val key = "$showId:$width"
        memory.get(key)?.let { return it }
        val f = posterFile(ctx, showId)
        if (!f.exists()) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val src = BitmapFactory.decodeFile(f.path, opts) ?: return null
            val h = src.height * width / src.width
            val out = if (src.width != width) Bitmap.createScaledBitmap(src, width, h, true) else src
            memory.put(key, out)
            out
        }.getOrNull()
    }
}
