package com.just4fun2u.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistência simples da biblioteca em um arquivo JSON no armazenamento interno.
 * Leve o suficiente para centenas de itens sem precisar de banco de dados.
 */
object LibraryStore {

    val books = mutableListOf<Book>()
    val collections = mutableListOf<BookCollection>()

    private var loaded = false
    private lateinit var storeFile: File
    private lateinit var booksDirFile: File
    private lateinit var coversDirFile: File

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        storeFile = File(app.filesDir, "library.json")
        booksDirFile = File(app.filesDir, "books").apply { mkdirs() }
        coversDirFile = File(app.filesDir, "covers").apply { mkdirs() }
        load()
        loaded = true
    }

    fun booksDir(): File = booksDirFile
    fun coversDir(): File = coversDirFile

    fun bookFile(book: Book): File = File(booksDirFile, book.fileName)
    fun coverFile(book: Book): File? = book.coverName?.let { File(coversDirFile, it) }

    fun findBook(id: String): Book? = books.find { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    @Synchronized
    fun addBook(book: Book) {
        books.add(book)
        save()
    }

    @Synchronized
    fun removeBook(book: Book) {
        books.remove(book)
        bookFile(book).delete()
        coverFile(book)?.delete()
        save()
    }

    @Synchronized
    fun addCollection(name: String): BookCollection {
        val c = BookCollection(newId(), name)
        collections.add(c)
        save()
        return c
    }

    @Synchronized
    fun removeCollection(collection: BookCollection) {
        collections.remove(collection)
        books.forEach { it.collectionIds.remove(collection.id) }
        save()
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        val cols = JSONArray()
        collections.forEach { c ->
            cols.put(JSONObject().put("id", c.id).put("name", c.name))
        }
        val bks = JSONArray()
        books.forEach { b ->
            bks.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("fileName", b.fileName)
                    .put("type", b.type.name)
                    .put("coverName", b.coverName ?: JSONObject.NULL)
                    .put("addedAt", b.addedAt)
                    .put("lastPage", b.lastPage)
                    .put("pageCount", b.pageCount)
                    .put("lastChapter", b.lastChapter)
                    .put("lastScroll", b.lastScroll)
                    .put("collections", JSONArray(b.collectionIds.toList()))
            )
        }
        root.put("collections", cols)
        root.put("books", bks)
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(storeFile)
    }

    private fun load() {
        books.clear()
        collections.clear()
        if (!storeFile.exists()) return
        try {
            val root = JSONObject(storeFile.readText())
            val cols = root.optJSONArray("collections") ?: JSONArray()
            for (i in 0 until cols.length()) {
                val o = cols.getJSONObject(i)
                collections.add(BookCollection(o.getString("id"), o.getString("name")))
            }
            val bks = root.optJSONArray("books") ?: JSONArray()
            for (i in 0 until bks.length()) {
                val o = bks.getJSONObject(i)
                val colIds = mutableSetOf<String>()
                val ca = o.optJSONArray("collections") ?: JSONArray()
                for (j in 0 until ca.length()) colIds.add(ca.getString(j))
                books.add(
                    Book(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        fileName = o.getString("fileName"),
                        type = BookType.valueOf(o.getString("type")),
                        coverName = if (o.isNull("coverName")) null else o.getString("coverName"),
                        addedAt = o.optLong("addedAt"),
                        lastPage = o.optInt("lastPage"),
                        pageCount = o.optInt("pageCount"),
                        lastChapter = o.optInt("lastChapter"),
                        lastScroll = o.optInt("lastScroll"),
                        collectionIds = colIds
                    )
                )
            }
        } catch (_: Exception) {
            // arquivo corrompido: começa vazio em vez de travar o app
        }
    }
}
