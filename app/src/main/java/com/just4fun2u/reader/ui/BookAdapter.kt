package com.just4fun2u.reader.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.just4fun2u.reader.R
import com.just4fun2u.reader.data.Book
import com.just4fun2u.reader.data.LibraryStore
import com.just4fun2u.reader.data.LibraryView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookAdapter(
    var viewMode: LibraryView,
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    private val items = mutableListOf<Book>()
    private val scope = CoroutineScope(Dispatchers.Main)

    @Suppress("NotifyDataSetChanged")
    fun submit(books: List<Book>) {
        items.clear()
        items.addAll(books)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = viewMode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = when (LibraryView.entries[viewType]) {
            LibraryView.GRID -> R.layout.item_book
            LibraryView.LIST -> R.layout.item_book_list
            LibraryView.CIRCLE -> R.layout.item_book_circle
        }
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val badge: TextView? = view.findViewById(R.id.badge)
        private val progress: TextView? = view.findViewById(R.id.progress)
        private var job: Job? = null

        fun bind(book: Book) {
            title.text = book.title
            badge?.text = book.type.name
            progress?.let { p ->
                p.text = if (book.type.isComic && book.pageCount > 0) {
                    itemView.context.getString(
                        R.string.page_progress, book.lastPage + 1, book.pageCount
                    )
                } else ""
                p.visibility = if (p.text.isEmpty()) View.GONE else View.VISIBLE
            }

            itemView.setOnClickListener { onClick(book) }
            itemView.setOnLongClickListener { onLongClick(book); true }

            job?.cancel()
            cover.setImageResource(R.drawable.cover_placeholder)
            val coverFile = LibraryStore.coverFile(book)
            if (coverFile != null && coverFile.exists()) {
                job = scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(coverFile.absolutePath)
                    }
                    if (bmp != null) cover.setImageBitmap(bmp)
                }
            }
        }
    }
}
