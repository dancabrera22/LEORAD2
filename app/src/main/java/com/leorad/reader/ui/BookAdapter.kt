package com.leorad.reader.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.leorad.reader.R
import com.leorad.reader.data.Book
import com.leorad.reader.data.LibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    private val items = mutableListOf<Book>()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun submit(books: List<Book>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = books.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == books[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == books[n]
        })
        items.clear()
        items.addAll(books)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val badge: TextView = view.findViewById(R.id.badge)
        private val progress: TextView = view.findViewById(R.id.progress)
        private var job: Job? = null

        fun bind(book: Book) {
            title.text = book.title
            badge.text = book.type.name
            progress.text = if (book.type.isComic && book.pageCount > 0) {
                itemView.context.getString(
                    R.string.page_progress, book.lastPage + 1, book.pageCount
                )
            } else ""
            progress.visibility = if (progress.text.isEmpty()) View.GONE else View.VISIBLE

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
