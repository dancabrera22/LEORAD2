package com.just4fun2u.reader

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.just4fun2u.reader.data.Book
import com.just4fun2u.reader.data.ComicMode
import com.just4fun2u.reader.data.LibraryStore
import com.just4fun2u.reader.data.Prefs
import com.just4fun2u.reader.data.ReadFilter
import com.just4fun2u.reader.format.ComicSource
import com.just4fun2u.reader.ui.Filters
import com.just4fun2u.reader.ui.SoftPageView
import com.just4fun2u.reader.ui.ZoomableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComicReaderActivity : AppCompatActivity() {

    private var book: Book? = null
    private var source: ComicSource? = null
    private lateinit var pager: ViewPager2
    private lateinit var softPager: SoftPageView
    private lateinit var scrollRecycler: RecyclerView
    private lateinit var overlay: View
    private lateinit var pageLabel: TextView
    private lateinit var seekBar: SeekBar
    private val scope = CoroutineScope(Dispatchers.Main)

    private var mode: ComicMode = ComicMode.FLIP
    private var filter: ReadFilter = ReadFilter.NONE
    private var currentPage = 0
    private var suppressSeekCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryStore.init(this)
        Prefs.init(this)
        setContentView(R.layout.activity_comic)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        pager = findViewById(R.id.pager)
        softPager = findViewById(R.id.softPager)
        scrollRecycler = findViewById(R.id.scrollRecycler)
        overlay = findViewById(R.id.overlay)
        pageLabel = findViewById(R.id.pageLabel)
        seekBar = findViewById(R.id.seekBar)
        val titleLabel = findViewById<TextView>(R.id.titleLabel)
        val loading = findViewById<ProgressBar>(R.id.loading)

        mode = Prefs.comicMode
        filter = Prefs.comicFilter

        val bookId = intent.getStringExtra("bookId")
        val b = bookId?.let { LibraryStore.findBook(it) }
        if (b == null) {
            finish()
            return
        }
        book = b
        titleLabel.text = b.title
        currentPage = b.lastPage

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
            currentPage = currentPage.coerceIn(0, opened.pageCount - 1)
            setupReaders(opened)
            applyMode(jumpToCurrent = true)
        }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsSheet() }
        com.just4fun2u.reader.ui.Gestures.blockEdgeGestures(findViewById(android.R.id.content))
        hideSystemBars()
    }

    // ---------- montagem ----------

    private fun setupReaders(src: ComicSource) {
        pager.adapter = PageAdapter(src)
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (mode == ComicMode.SLIDE) onPageShown(position)
            }
        })

        softPager.onPageChanged = { if (mode == ComicMode.FLIP) onPageShown(it) }
        softPager.onSingleTap = { toggleOverlay() }
        softPager.pageColorFilter = Filters.colorFilter(filter)
        softPager.setSource(object : SoftPageView.PageSource {
            override val count get() = src.pageCount
            override fun requestPage(index: Int, cb: (android.graphics.Bitmap?) -> Unit) {
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            decodeSampled(src.pageBytes(index))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    cb(bmp)
                }
            }
        }, currentPage)

        scrollRecycler.layoutManager = LinearLayoutManager(this)
        scrollRecycler.adapter = ScrollAdapter(src)
        scrollRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (mode != ComicMode.SCROLL) return
                val lm = rv.layoutManager as LinearLayoutManager
                val first = lm.findFirstVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION) onPageShown(first)
            }
        })

        seekBar.max = src.pageCount - 1
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekCallback) return
                jumpTo(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun onPageShown(position: Int) {
        currentPage = position
        book?.lastPage = position
        suppressSeekCallback = true
        seekBar.progress = position
        suppressSeekCallback = false
        source?.let { pageLabel.text = getString(R.string.page_progress, position + 1, it.pageCount) }
    }

    private fun jumpTo(position: Int) {
        currentPage = position
        when (mode) {
            ComicMode.SCROLL -> {
                (scrollRecycler.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(position, 0)
                onPageShown(position)
            }
            ComicMode.FLIP -> softPager.jumpTo(position)
            ComicMode.SLIDE -> pager.setCurrentItem(position, false)
        }
    }

    private fun applyMode(jumpToCurrent: Boolean) {
        pager.visibility = if (mode == ComicMode.SLIDE) View.VISIBLE else View.GONE
        softPager.visibility = if (mode == ComicMode.FLIP) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scrollZoom).visibility =
            if (mode == ComicMode.SCROLL) View.VISIBLE else View.GONE
        if (jumpToCurrent) jumpTo(currentPage)
        onPageShown(currentPage)
    }

    private fun applyFilterToVisible() {
        val cf = Filters.colorFilter(filter)
        softPager.pageColorFilter = cf
        val groups = mutableListOf<ViewGroup>(scrollRecycler)
        (pager.getChildAt(0) as? ViewGroup)?.let { groups.add(it) }
        groups.forEach { group ->
            for (i in 0 until group.childCount) {
                group.getChildAt(i).findViewById<ImageView>(R.id.pageImage)?.colorFilter = cf
            }
        }
    }

    // ---------- ajustes ----------

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_reader_settings, null)
        sheet.setContentView(view)

        val modeGroup = view.findViewById<ChipGroup>(R.id.modeGroup)
        val modeLabels = mapOf(
            ComicMode.FLIP to getString(R.string.mode_flip),
            ComicMode.SLIDE to getString(R.string.mode_slide),
            ComicMode.SCROLL to getString(R.string.mode_scroll)
        )
        ComicMode.entries.forEach { m ->
            modeGroup.addView(makeChoiceChip(modeLabels[m]!!, m == mode) {
                mode = m
                Prefs.comicMode = m
                applyMode(jumpToCurrent = true)
            })
        }

        val filterGroup = view.findViewById<ChipGroup>(R.id.filterGroup)
        ReadFilter.entries.forEach { f ->
            filterGroup.addView(makeChoiceChip(Filters.label(this, f), f == filter) {
                filter = f
                Prefs.comicFilter = f
                applyFilterToVisible()
            })
        }
        sheet.show()
    }

    private fun makeChoiceChip(text: String, checked: Boolean, onPick: () -> Unit): Chip {
        return Chip(this).apply {
            this.text = text
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            setOnClickListener {
                isChecked = true
                onPick()
            }
        }
    }

    // ---------- overlay / sistema ----------

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

    // ---------- modo paginado ----------

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
                image.colorFilter = Filters.colorFilter(filter)
                spinner.visibility = View.VISIBLE
                job = scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            decodeSampled(src.pageBytes(position))
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

    // ---------- modo rolagem ----------

    inner class ScrollAdapter(private val src: ComicSource) :
        RecyclerView.Adapter<ScrollAdapter.ScrollHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScrollHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comic_scroll, parent, false)
            return ScrollHolder(v)
        }

        override fun getItemCount() = src.pageCount

        override fun onBindViewHolder(holder: ScrollHolder, position: Int) {
            holder.bind(position)
        }

        override fun onViewRecycled(holder: ScrollHolder) {
            holder.unbind()
        }

        inner class ScrollHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val image: ImageView = view.findViewById(R.id.pageImage)
            private var job: Job? = null

            init {
                image.setOnClickListener { toggleOverlay() }
            }

            fun bind(position: Int) {
                job?.cancel()
                image.setImageDrawable(null)
                image.colorFilter = Filters.colorFilter(filter)
                job = scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            decodeSampled(src.pageBytes(position))
                        } catch (_: Exception) {
                            null
                        }
                    }
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
