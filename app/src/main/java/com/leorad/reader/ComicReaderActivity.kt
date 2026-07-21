package com.leorad.reader

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.leorad.reader.data.Book
import com.leorad.reader.data.LibraryStore
import com.leorad.reader.format.ComicSource
import com.leorad.reader.ui.ZoomableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComicReaderActivity : AppCompatActivity() {

    private var book: Book? = null
    private var source: ComicSource? = null
    private lateinit var pager: ViewPager2
    private lateinit var overlay: View
    private lateinit var pageLabel: TextView
    private lateinit var seekBar: SeekBar
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryStore.init(this)
        setContentView(R.layout.activity_comic)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        pager = findViewById(R.id.pager)
        overlay = findViewById(R.id.overlay)
        pageLabel = findViewById(R.id.pageLabel)
        seekBar = findViewById(R.id.seekBar)
        val titleLabel = findViewById<TextView>(R.id.titleLabel)
        val loading = findViewById<ProgressBar>(R.id.loading)

        val bookId = intent.getStringExtra("bookId")
        val b = bookId?.let { LibraryStore.findBook(it) }
        if (b == null) {
            finish()
            return
        }
        book = b
        titleLabel.text = b.title

        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                try {
                    ComicSource.open(LibraryStore.bookFile(b), b.type)
                } catch (e: Exception) {
                    null
                }
            }
            loading.visibility = View.GONE
            if (opened == null || opened.pageCount == 0) {
                Toast.makeText(
                    this@ComicReaderActivity, R.string.error_opening_comic, Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            source = opened
            b.pageCount = opened.pageCount
            LibraryStore.save()
            setupPager(opened, b)
        }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        hideSystemBars()
    }

    private fun setupPager(src: ComicSource, b: Book) {
        pager.adapter = PageAdapter(src)
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(b.lastPage.coerceIn(0, src.pageCount - 1), false)

        seekBar.max = src.pageCount - 1
        seekBar.progress = pager.currentItem
        updateLabel(pager.currentItem, src.pageCount)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                seekBar.progress = position
                updateLabel(position, src.pageCount)
                b.lastPage = position
            }
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) pager.setCurrentItem(value, false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateLabel(position: Int, count: Int) {
        pageLabel.text = getString(R.string.page_progress, position + 1, count)
    }

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
            hideSystemBars()
        } else {
            overlay.visibility = View.VISIBLE
            showSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        super.onPause()
        book?.let { LibraryStore.save() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            source?.close()
        } catch (_: Exception) {
        }
    }

    inner class PageAdapter(private val src: ComicSource) :
        RecyclerView.Adapter<PageAdapter.PageHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comic_page, parent, false)
            return PageHolder(v)
        }

        override fun getItemCount() = src.pageCount

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(position)
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.unbind()
        }

        inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val image: ZoomableImageView = view.findViewById(R.id.pageImage)
            private val spinner: ProgressBar = view.findViewById(R.id.pageLoading)
            private var job: Job? = null

            init {
                image.onSingleTap = { toggleOverlay() }
            }

            fun bind(position: Int) {
                job?.cancel()
                image.setImageDrawable(null)
                spinner.visibility = View.VISIBLE
                job = scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            val bytes = src.pageBytes(position)
                            decodeSampled(bytes)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    spinner.visibility = View.GONE
                    if (bmp != null) image.setImageBitmap(bmp)
                }
            }

            fun unbind() {
                job?.cancel()
                image.setImageDrawable(null)
            }
        }
    }

    private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
        val metrics = resources.displayMetrics
        val maxDim = (maxOf(metrics.widthPixels, metrics.heightPixels) * 2).coerceAtMost(4096)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
