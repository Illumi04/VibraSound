package com.example.extractor

data class SearchResult(
    val title: String,
    val artist: String,
    val url: String,
    val durationSeconds: Long = 0,
    val thumbnailUrl: String = ""
) {
    // Alias en español para conveniencia según especificación
    val titulo: String get() = title
    val artista: String get() = artist
}
