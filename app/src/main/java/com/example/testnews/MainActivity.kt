package com.example.testnews

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

// The address of your PWA on GitHub Pages (must end with a slash). Both apps use the
// same files from the same address, so the content and hosting are identical.
const val BASE_URL = "https://muktarabdul977-glitch.github.io/PWA/"

class Article(val id: Int, val title: String, val summary: String, val body: String, val image: String)

/**
 * Native version of the Test News App.
 * Behaviour matches the PWA:
 *  - article list: network first, saved copy used if the network fails
 *  - images: saved on first view, then read from the saved copy (cache first)
 *  - images load only for the rows that are shown on screen (lazy loading)
 *  - tap an article to show or hide its body
 * A measurement box at the top shows ready time, bytes and storage (tap it to hide it).
 */
class MainActivity : Activity() {

    private val startMs = SystemClock.elapsedRealtime()      // timer starts when the activity is created
    private val bytes = AtomicLong(0)                         // body bytes received from the network
    private var bytesAtReady = -1L
    private var readyMs = -1L

    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val memCache = LruCache<String, Bitmap>(24)

    private lateinit var metrics: TextView
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val header = TextView(this)
        header.text = "Test News App"
        header.textSize = 20f
        header.setTextColor(Color.WHITE)
        header.setBackgroundColor(Color.parseColor("#1A73E8"))
        header.setPadding(dp(16), dp(14), dp(16), dp(14))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        metrics = TextView(this)
        metrics.textSize = 11f
        metrics.typeface = Typeface.MONOSPACE
        metrics.setBackgroundColor(Color.parseColor("#FFF8E1"))
        metrics.setTextColor(Color.BLACK)
        metrics.setPadding(dp(12), dp(8), dp(12), dp(8))
        metrics.setOnClickListener { metrics.visibility = View.GONE }
        root.addView(metrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = ListView(this)
        list.divider = null
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        startMetricsLoop()
        loadArticles()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ----- loading the article list (network first, saved copy as fallback) -----------------------
    private fun loadArticles() {
        io.execute {
            val saved = File(filesDir, "articles.json")
            var text: String? = null
            try {
                val data = download(BASE_URL + "articles.json")
                saved.writeBytes(data)
                text = String(data, Charsets.UTF_8)
            } catch (e: Exception) {
                if (saved.exists()) text = saved.readText()
            }
            val articles = if (text != null) parse(text) else emptyList()
            ui.post { showArticles(articles) }
        }
    }

    private fun parse(text: String): List<Article> {
        val arr = JSONArray(text)
        val result = ArrayList<Article>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(Article(o.getInt("id"), o.getString("title"), o.optString("summary"), o.optString("body"), o.getString("image")))
        }
        return result
    }

    private fun showArticles(articles: List<Article>) {
        val adapter = ArticleAdapter(articles)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> adapter.toggle(position) }

        // "Ready" = the first title has been painted: wait two frames after the list is shown
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            choreographer.postFrameCallback {
                if (readyMs < 0 && articles.isNotEmpty()) {
                    readyMs = SystemClock.elapsedRealtime() - startMs
                    bytesAtReady = bytes.get()
                }
            }
        }
    }

    // ----- network: counts the bytes that actually travel (compressed if the server compresses) ----
    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.useCaches = false
        conn.setRequestProperty("Accept-Encoding", "gzip")  // we decompress ourselves so we can count wire bytes
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytes.addAndGet(n.toLong())
                }
            }
            val raw = out.toByteArray()
            return if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            } else {
                raw
            }
        } finally {
            conn.disconnect()
        }
    }

    // ----- images: saved on first view, then read from the saved copy ------------------------------
    private fun loadImage(img: ImageView, path: String) {
        img.tag = path
        img.setImageBitmap(null)
        val cached = memCache.get(path)
        if (cached != null) {
            img.setImageBitmap(cached)
            return
        }
        io.execute {
            val file = File(filesDir, "img_" + path.replace('/', '_'))
            try {
                if (!file.exists()) file.writeBytes(download(BASE_URL + path))
                val bmp = BitmapFactory.decodeFile(file.path)
                if (bmp != null) {
                    memCache.put(path, bmp)
                    ui.post { if (img.tag == path) img.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
                // offline and not saved yet: the grey placeholder stays
            }
        }
    }

    // ----- list adapter ---------------------------------------------------------------------------
    inner class ArticleAdapter(private val items: List<Article>) : BaseAdapter() {
        private val expanded = HashSet<Int>()

        fun toggle(position: Int) {
            val id = items[position].id
            if (!expanded.remove(id)) expanded.add(id)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val a = items[position]
            val img = row.getChildAt(0) as ImageView
            val title = row.getChildAt(1) as TextView
            val summary = row.getChildAt(2) as TextView
            val body = row.getChildAt(3) as TextView
            title.text = a.title
            summary.text = a.summary
            body.text = a.body
            body.visibility = if (expanded.contains(a.id)) View.VISIBLE else View.GONE
            loadImage(img, a.image)
            return row
        }

        private fun makeRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))

            val img = ImageView(this@MainActivity)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setBackgroundColor(Color.parseColor("#E0E0E0"))
            row.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))

            val title = TextView(this@MainActivity)
            title.textSize = 18f
            title.setTypeface(Typeface.DEFAULT_BOLD)
            title.setTextColor(Color.BLACK)
            title.setPadding(0, dp(8), 0, dp(4))
            row.addView(title)

            val summary = TextView(this@MainActivity)
            summary.textSize = 14f
            summary.setTextColor(Color.DKGRAY)
            row.addView(summary)

            val body = TextView(this@MainActivity)
            body.textSize = 14f
            body.setTextColor(Color.BLACK)
            body.setPadding(0, dp(8), 0, 0)
            row.addView(body)
            return row
        }
    }

    // ----- measurement box (refreshes every second for 30 seconds) ---------------------------------
    private fun startMetricsLoop() {
        val begin = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                updateMetrics()
                if (SystemClock.elapsedRealtime() - begin < 30000) ui.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    private fun updateMetrics() {
        val ready = if (readyMs < 0) "waiting..." else "$readyMs ms"
        val atReady = if (bytesAtReady < 0) "waiting..." else kb(bytesAtReady)
        val stored = dirSize(filesDir) + dirSize(cacheDir)
        val apk = File(applicationInfo.sourceDir).length()
        val seconds = (SystemClock.elapsedRealtime() - startMs) / 1000
        metrics.text =
            "Ready (first title painted): $ready\n" +
            "Network bytes at ready: $atReady\n" +
            "Network bytes now: ${kb(bytes.get())} (body bytes)\n" +
            "Stored data (files + cache): ${mb(stored)}\n" +
            "Installed package (APK): ${mb(apk)}\n" +
            "Seconds since start: $seconds\n" +
            "(tap this box to hide it)"
    }

    private fun kb(b: Long): String = String.format(Locale.US, "%.1f KB", b / 1024.0)
    private fun mb(b: Long): String = String.format(Locale.US, "%.2f MB", b / 1024.0 / 1024.0)

    private fun dirSize(dir: File): Long {
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) total += if (f.isDirectory) dirSize(f) else f.length()
        return total
    }
}
