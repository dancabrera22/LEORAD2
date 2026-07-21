package com.just4fun2u.reader

import android.annotation.SuppressLint
import android.os.Bundle
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
import com.just4fun2u.reader.data.Book
import com.just4fun2u.reader.data.BookType
import com.just4fun2u.reader.data.LibraryStore
import com.just4fun2u.reader.format.EpubBook
import com.just4fun2u.reader.format.MobiBook
import com.just4fun2u.reader.format.UnsupportedFormatException
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
    private lateinit var chapterLabel: TextView
    private lateinit var bottomBar: View
    private var currentChapter = 0
    private var pendingScroll = 0
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val EPUB_HOST = "just4fun2u.epub"
        private const val INJECT_CSS =
            "body{padding:16px 20px;line-height:1.6;max-width:46em;margin:0 auto;" +
                "word-wrap:break-word;} img{max-width:100%;height:auto;}"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryStore.init(this)
        setContentView(R.layout.activity_book)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        webView = findViewById(R.id.webView)
        chapterLabel = findViewById(R.id.chapterLabel)
        bottomBar = findViewById(R.id.bottomBar)
        val loading = findViewById<ProgressBar>(R.id.loading)

        webView.settings.javaScriptEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

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
                }
            }

            findViewById<View>(R.id.btnPrev).setOnClickListener { openChapter(currentChapter - 1) }
            findViewById<View>(R.id.btnNext).setOnClickListener { openChapter(currentChapter + 1) }

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
        chapterLabel.text = getString(R.string.chapter_progress, currentChapter + 1, e.spine.size)
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
                }
            }
            val page = "<html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>$INJECT_CSS</style></head><body>${mobi.html}</body></html>"
            webView.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
        }
    }

    // ---------- comum ----------

    private fun injectStyleAndRestore() {
        val js = """
            (function(){
              if(!document.getElementById('just4fun2u-style')){
                var s=document.createElement('style');
                s.id='just4fun2u-style';
                s.textContent='${INJECT_CSS.replace("'", "\\'")}';
                document.head.appendChild(s);
              }
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
            if (pendingScroll > 0) {
                webView.postDelayed({ webView.scrollTo(0, pendingScroll) }, 120)
                pendingScroll = 0
            }
        }
    }

    override fun onPause() {
        super.onPause()
        book?.let {
            it.lastChapter = currentChapter
            it.lastScroll = webView.scrollY
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
