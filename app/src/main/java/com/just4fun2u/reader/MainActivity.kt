package com.just4fun2u.reader

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.just4fun2u.reader.data.Book
import com.just4fun2u.reader.data.BookCollection
import com.just4fun2u.reader.data.BookImporter
import com.just4fun2u.reader.data.LibraryStore
import com.just4fun2u.reader.data.LibraryView
import com.just4fun2u.reader.data.Prefs
import com.just4fun2u.reader.ui.BookAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BookAdapter
    private lateinit var chipGroup: ChipGroup
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView

    /** id da coleção selecionada, ou null = todos */
    private var selectedCollection: String? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) importFiles(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryStore.init(this)
        Prefs.init(this)
        setContentView(R.layout.activity_main)

        chipGroup = findViewById(R.id.chipGroup)
        emptyView = findViewById(R.id.emptyView)
        recycler = findViewById(R.id.recycler)

        adapter = BookAdapter(
            viewMode = Prefs.libraryView,
            onClick = { openBook(it) },
            onLongClick = { showBookMenu(it) }
        )
        applyViewMode(Prefs.libraryView)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_view_mode) {
                showViewModeMenu(toolbar)
                true
            } else false
        }

        findViewById<ExtendedFloatingActionButton>(R.id.fabAdd).setOnClickListener {
            openDocuments.launch(arrayOf("*/*"))
        }

        rebuildChips()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun applyViewMode(mode: LibraryView) {
        Prefs.libraryView = mode
        adapter.viewMode = mode
        recycler.layoutManager = when (mode) {
            LibraryView.GRID -> GridLayoutManager(this, spanFor(140))
            LibraryView.LIST -> LinearLayoutManager(this)
            LibraryView.CIRCLE -> GridLayoutManager(this, spanFor(112))
        }
        recycler.adapter = adapter
    }

    private fun spanFor(itemDp: Int): Int =
        (resources.displayMetrics.widthPixels /
            (itemDp * resources.displayMetrics.density)).toInt().coerceAtLeast(2)

    private fun showViewModeMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_view_modes, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.view_grid -> applyViewMode(LibraryView.GRID)
                R.id.view_list -> applyViewMode(LibraryView.LIST)
                R.id.view_circle -> applyViewMode(LibraryView.CIRCLE)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun refresh() {
        val filtered = LibraryStore.books
            .filter { selectedCollection == null || it.collectionIds.contains(selectedCollection) }
            .sortedByDescending { it.addedAt }
        adapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = getString(
            if (selectedCollection == null) R.string.empty_library else R.string.empty_collection
        )
    }

    // ---------- coleções ----------

    private fun rebuildChips() {
        chipGroup.removeAllViews()

        val allChip = makeChip(getString(R.string.all_books), selectedCollection == null)
        allChip.setOnClickListener {
            selectedCollection = null
            rebuildChips()
            refresh()
        }
        chipGroup.addView(allChip)

        LibraryStore.collections.forEach { collection ->
            val chip = makeChip(collection.name, selectedCollection == collection.id)
            chip.setOnClickListener {
                selectedCollection = collection.id
                rebuildChips()
                refresh()
            }
            chip.setOnLongClickListener {
                showCollectionMenu(collection)
                true
            }
            chipGroup.addView(chip)
        }

        val newChip = makeChip(getString(R.string.new_collection), false)
        newChip.setOnClickListener { promptNewCollection() }
        chipGroup.addView(newChip)
    }

    private fun makeChip(text: String, checked: Boolean): Chip {
        return Chip(this).apply {
            this.text = text
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
        }
    }

    private fun promptNewCollection() {
        promptText(getString(R.string.new_collection_title), "") { name ->
            if (name.isNotBlank()) {
                val c = LibraryStore.addCollection(name.trim())
                selectedCollection = c.id
                rebuildChips()
                refresh()
            } else {
                rebuildChips()
            }
        }
    }

    private fun showCollectionMenu(collection: BookCollection) {
        val options = arrayOf(getString(R.string.rename), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(collection.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptText(getString(R.string.rename), collection.name) { name ->
                        if (name.isNotBlank()) {
                            collection.name = name.trim()
                            LibraryStore.save()
                        }
                        rebuildChips()
                    }
                    1 -> MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.delete_collection_confirm, collection.name))
                        .setMessage(R.string.delete_collection_note)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            if (selectedCollection == collection.id) selectedCollection = null
                            LibraryStore.removeCollection(collection)
                            rebuildChips()
                            refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    // ---------- livros ----------

    private fun openBook(book: Book) {
        val intent = if (book.type.isComic) {
            Intent(this, ComicReaderActivity::class.java)
        } else {
            Intent(this, BookReaderActivity::class.java)
        }
        intent.putExtra("bookId", book.id)
        startActivity(intent)
    }

    private fun showBookMenu(book: Book) {
        val options = arrayOf(
            getString(R.string.manage_collections),
            getString(R.string.rename),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(book.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCollectionPicker(book)
                    1 -> promptText(getString(R.string.rename), book.title) { name ->
                        if (name.isNotBlank()) {
                            book.title = name.trim()
                            LibraryStore.save()
                            refresh()
                        }
                    }
                    2 -> MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.delete_book_confirm, book.title))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            LibraryStore.removeBook(book)
                            refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun showCollectionPicker(book: Book) {
        if (LibraryStore.collections.isEmpty()) {
            promptText(getString(R.string.new_collection_title), "") { name ->
                if (name.isNotBlank()) {
                    val c = LibraryStore.addCollection(name.trim())
                    book.collectionIds.add(c.id)
                    LibraryStore.save()
                    rebuildChips()
                    refresh()
                }
            }
            return
        }
        val names = LibraryStore.collections.map { it.name }.toTypedArray()
        val checked = LibraryStore.collections
            .map { book.collectionIds.contains(it.id) }
            .toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_collections)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val id = LibraryStore.collections[which].id
                if (isChecked) book.collectionIds.add(id) else book.collectionIds.remove(id)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LibraryStore.save()
                refresh()
            }
            .setNeutralButton(R.string.new_collection_title) { _, _ ->
                promptText(getString(R.string.new_collection_title), "") { name ->
                    if (name.isNotBlank()) {
                        val c = LibraryStore.addCollection(name.trim())
                        book.collectionIds.add(c.id)
                        LibraryStore.save()
                        rebuildChips()
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun importFiles(uris: List<android.net.Uri>) {
        val progress = MaterialAlertDialogBuilder(this)
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()
        scope.launch {
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    try {
                        BookImporter.import(this@MainActivity, uri)
                        ok++
                    } catch (_: Exception) {
                        failed++
                    }
                }
            }
            progress.dismiss()
            refresh()
            val msg = if (failed == 0) {
                resources.getQuantityString(R.plurals.imported_ok, ok, ok)
            } else {
                getString(R.string.imported_with_errors, ok, failed)
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptText(title: String, initial: String, onDone: (String) -> Unit) {
        val layout = TextInputLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(initial)
            setSelection(initial.length)
        }
        layout.addView(edit)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onDone(edit.text?.toString() ?: "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
