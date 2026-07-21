package com.just4fun2u.reader.data

enum class BookType {
    CBZ, CBR, EPUB, MOBI;

    val isComic: Boolean get() = this == CBZ || this == CBR
}

data class Book(
    val id: String,
    var title: String,
    val fileName: String,
    val type: BookType,
    var coverName: String? = null,
    val addedAt: Long,
    // progresso de leitura
    var lastPage: Int = 0,       // quadrinhos
    var pageCount: Int = 0,      // quadrinhos
    var lastChapter: Int = 0,    // epub
    var lastScroll: Int = 0,     // epub/mobi
    val collectionIds: MutableSet<String> = mutableSetOf()
)

data class BookCollection(
    val id: String,
    var name: String
)
