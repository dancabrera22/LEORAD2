package com.just4fun2u.reader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.just4fun2u.reader.data.Book
import com.just4fun2u.reader.data.BookMode
import com.just4fun2u.reader.data.BookType
import com.just4fun2u.reader.data.LibraryStore
import com.just4fun2u.reader.data.Prefs
import com.just4fun2u.reader.data.ReadFilter
import com.just4fun2u.reader.format.EpubBook
import com.just4fun2u.reader.format.MobiBook
import com.just4fun2u.reader.format.UnsupportedFormatException
import com.just4fun2u.reader.ui.Filters
import com.just4fun2u.reader.ui.Gestures
import com.just4fun2u.reader.ui.SoftPageView
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Leitor de EPUB e MOBI usando WebView. */
class BookReaderActivity : AppCompatActivity() {

    private var book: Book? = null
    private var epub: EpubBook? = null
    private lateinit var webView: WebView
    private lateinit var softPager: SoftPageView
    private lateinit var chapterLabel: TextView
    private lateinit var bottomBar: View
    private var currentChapter = 0
    private var pendingScroll = 0
    private var filter: ReadFilter = ReadFilter.NONE
    private var bookMode: BookMode = BookMode.PAGED
    private val scope = CoroutineScope(Dispatchers.Main)

    // paginação (modo páginas)
    private var pageCount = 1
    /** página-alvo após carregar um capítulo: null = restaurar salva, -1 = última */
    private var pendingTargetPage: Int? = null
    private var firstLoad = true
    private val snapQueue = ArrayDeque<Pair<Int, (Bitmap?) -> Unit>>()
    private var snapping = false

    companion object {
        private const val EPUB_HOST = "just4fun2u.epub"
        private const val INJECT_CSS =
            "body{padding:16px 20px;line-height:1.6;max-width:46em;margin:0 auto;" +
                "word-wrap:break-word;} img{max-width:100%;height:auto;}"
        private const val PAGINATE_JS = """
            (function(){
              var s=document.getElementById('j4f-page-style');
              if(!s){
                s=document.createElement('style');
                s.id='j4f-page-style';
                document.head.appendChild(s);
              }
              s.textContent='html{height:100%;overflow:hidden;}'+
                'body{margin:0 !important;padding:22px 22px !important;'+
                'box-sizing:border-box !important;height:100% !important;'+
                'max-width:none !important;column-width:calc(100vw - 44px);'+
                'column-gap:44px;column-fill:auto;}'+
                'img{max-width:100% !important;max-height:85vh !important;}';
              return Math.max(1, Math.ceil((document.body.scrollWidth - 20) / window.innerWidth));
            })();
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryStore.init(this)
        Prefs.init(this)
        filter = Prefs.bookFilter
        setContentView(R.layout.activity_book)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        webView = findViewById(R.id.webView)
        softPager = findViewById(R.id.softPager)
        chapterLabel = findViewById(R.id.chapterLabel)
        bottomBar = findViewById(R.id.bottomBar)
        val loading = findViewById<ProgressBar>(R.id.loading)

        webView.settings.javaScriptEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        bookMode = Prefs.bookMode
        softPager.zoomEnabled = false
        softPager.onPageChanged = { idx ->
            book?.lastPage = idx
            updateChapterLabel()
        }
        softPager.onOverscrollForward = {
            val e = epub
            if (e != null && currentChapter < e.spine.size - 1) {
                pendingTargetPage = 0
                openChapter(currentChapter + 1)
            }
        }
        softPager.onOverscrollBackward = {
            if (epub != null && currentChapter > 0) {
                pendingTargetPage = -1
                openChapter(currentChapter - 1)
            }
        }

        // bloqueia os gestos de navegação do sistema durante a leitura:
        // as bordas ficam reservadas para virar as páginas
        Gestures.blockEdgeGestures(findViewById(android.R.id.content))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val b = intent.getStringExtra("bookId")?.let { LibraryStore.findBook(it) }
        if (b == null) {
            finish()
            return
        }
        book = b
        supportActionBar?.title = b.title
        currentChapter = b.lastChapter
        pendingScroll = b.lastScroll

        when (b.type) {
            BookType.EPUB -> loadEpub(b, loading)
            BookType.MOBI -> loadMobi(b, loading)
            else -> finish()
        }
    }

    // ---------- EPUB ----------

    private fun loadEpub(b: Book, loading: ProgressBar) {
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                try {
                    EpubBook(LibraryStore.bookFile(b))
                } catch (e: Exception) {
                    null
                }
            }
            loading.visibility = View.GONE
            if (opened == null || opened.spine.isEmpty()) {
                Toast.makeText(this@BookReaderActivity, R.string.error_opening_book, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            epub = opened

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.host != EPUB_HOST) return null
                    val path = url.path?.removePrefix("/") ?: return null
                    val bytes = opened.entryBytes(path)
                        ?: return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse(
                        EpubBook.mimeFor(path), "utf-8", ByteArrayInputStream(bytes)
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return false
                    if (url.host == EPUB_HOST) {
                        // link interno: acompanha o capítulo atual
                        val path = url.path?.removePrefix("/")
                        val idx = opened.spine.indexOf(path)
                        if (idx >= 0) {
                            currentChapter = idx
                            pendingScroll = 0
                            updateChapterLabel()
                        }
                        return false
                    }
                    return true // bloqueia links externos
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    injectStyleAndRestore()
                    if (bookMode == BookMode.PAGED) setupPagination()
                }
            }

            findViewById<View>(R.id.btnPrev).setOnClickListener {
                pendingTargetPage = 0
                openChapter(currentChapter - 1)
            }
            findViewById<View>(R.id.btnNext).setOnClickListener {
                pendingTargetPage = 0
                openChapter(currentChapter + 1)
            }

            openChapter(currentChapter.coerceIn(0, opened.spine.size - 1), restoreScroll = true)
        }
    }

    private fun openChapter(index: Int, restoreScroll: Boolean = false) {
        val e = epub ?: return
        if (index < 0 || index >= e.spine.size) return
        currentChapter = index
        if (!restoreScroll) pendingScroll = 0
        updateChapterLabel()
        webView.loadUrl("https://$EPUB_HOST/${e.spine[index]}")
    }

    private fun updateChapterLabel() {
        val e = epub ?: return
        val base = getString(R.string.chapter_progress, currentChapter + 1, e.spine.size)
        chapterLabel.text = if (bookMode == BookMode.PAGED && pageCount > 1) {
            "$base  ·  ${softPager.currentIndex + 1}/$pageCount"
        } else base
    }

    // ---------- modo paginado ----------

    private fun setupPagination() {
        // esconde o WebView atrás do paginador enquanto medimos
        webView.postDelayed({
            webView.evaluateJavascript(PAGINATE_JS) { result ->
                pageCount = result?.trim('"')?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val saved = book?.lastPage ?: 0
                val start = when (val target = pendingTargetPage) {
                    null -> if (firstLoad) saved.coerceIn(0, pageCount - 1) else 0
                    -1 -> pageCount - 1
                    else -> target.coerceIn(0, pageCount - 1)
                }
                pendingTargetPage = null
                firstLoad = false
                softPager.visibility = View.VISIBLE
                softPager.setSource(object : SoftPageView.PageSource {
                    override val count get() = pageCount
                    override fun requestPage(index: Int, cb: (Bitmap?) -> Unit) {
                        enqueueSnapshot(index, cb)
                    }
                }, start)
                updateChapterLabel()
            }
        }, 120)
    }

    private fun enqueueSnapshot(index: Int, cb: (Bitmap?) -> Unit) {
        snapQueue.add(index to cb)
        pumpSnapshots()
    }

    private fun pumpSnapshots() {
        if (snapping) return
        val (index, cb) = snapQueue.poll() ?: return
        snapping = true
        webView.evaluateJavascript(
            "window.scrollTo($index*window.innerWidth,0);"
        ) {
            webView.postDelayed({
                val bmp = try {
                    snapshotWebView()
                } catch (_: Exception) {
                    null
                }
                snapping = false
                cb(bmp)
                pumpSnapshots()
            }, 90)
        }
    }

    private fun snapshotWebView(): Bitmap? {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
        webView.draw(canvas)
        return bmp
    }

    /** Recaptura as páginas visíveis (após trocar filtro no modo paginado). */
    private fun refreshPagedSnapshots() {
        if (bookMode != BookMode.PAGED || softPager.visibility != View.VISIBLE) return
        snapQueue.clear()
        softPager.setSource(object : SoftPageView.PageSource {
            override val count get() = pageCount
            override fun requestPage(index: Int, cb: (Bitmap?) -> Unit) {
                enqueueSnapshot(index, cb)
            }
        }, softPager.currentIndex)
    }

    // ---------- MOBI ----------

    private fun loadMobi(b: Book, loading: ProgressBar) {
        bottomBar.visibility = View.GONE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(MobiBook(LibraryStore.bookFile(b)))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            loading.visibility = View.GONE
            val mobi = result.getOrNull()
            if (mobi == null) {
                val err = result.exceptionOrNull()
                val msg = if (err is UnsupportedFormatException) err.message
                else getString(R.string.error_opening_book)
                Toast.makeText(this@BookReaderActivity, msg, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ) = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    injectStyleAndRestore()
                    if (bookMode == BookMode.PAGED) setupPagination()
                }
            }
            val page = "<html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style id=\"just4fun2u-style\">${combinedCss()}</style></head>" +
                "<body>${mobi.html}</body></html>"
            webView.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
        }
    }

    // ---------- comum ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_book, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_filter) {
            showFilterSheet()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showFilterSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_reader_settings, null)
        sheet.setContentView(view)

        val modeGroup = view.findViewById<ChipGroup>(R.id.modeGroup)
        val modes = listOf(
            BookMode.PAGED to getString(R.string.mode_paged),
            BookMode.SCROLL to getString(R.string.mode_scroll)
        )
        modes.forEach { (m, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = m == bookMode
                isCheckedIconVisible = false
                setOnClickListener {
                    isChecked = true
                    if (m != bookMode) {
                        Prefs.bookMode = m
                        sheet.dismiss()
                        recreate()
                    }
                }
            }
            modeGroup.addView(chip)
        }

        val filterGroup = view.findViewById<ChipGroup>(R.id.filterGroup)
        ReadFilter.entries.forEach { f ->
            val chip = Chip(this).apply {
                text = Filters.label(this@BookReaderActivity, f)
                isCheckable = true
                isChecked = f == filter
                isCheckedIconVisible = false
                setOnClickListener {
                    isChecked = true
                    filter = f
                    Prefs.bookFilter = f
                    injectStyleAndRestore()
                    webView.postDelayed({ refreshPagedSnapshots() }, 200)
                }
            }
            filterGroup.addView(chip)
        }
        sheet.show()
    }

    private fun combinedCss(): String = INJECT_CSS + Filters.css(filter)

    private fun injectStyleAndRestore() {
        val css = combinedCss().replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function(){
              var s=document.getElementById('just4fun2u-style');
              if(!s){
                s=document.createElement('style');
                s.id='just4fun2u-style';
                document.head.appendChild(s);
              }
              s.textContent='$css';
              var m=document.querySelector('meta[name=viewport]');
              if(!m){
                m=document.createElement('meta');
                m.name='viewport';
                m.content='width=device-width, initial-scale=1';
                document.head.appendChild(m);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) {
            if (bookMode == BookMode.SCROLL && pendingScroll > 0) {
                webView.postDelayed({ webView.scrollTo(0, pendingScroll) }, 120)
                pendingScroll = 0
            }
        }
    }

    override fun onPause() {
        super.onPause()
        book?.let {
            it.lastChapter = currentChapter
            if (bookMode == BookMode.SCROLL) it.lastScroll = webView.scrollY
            LibraryStore.save()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            epub?.close()
        } catch (_: Exception) {
        }
    }
}
