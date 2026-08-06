package com.example.neonplayer.sources

enum class SortField { NAME, DATE, SIZE }

enum class SortDirection { ASCENDING, DESCENDING }

data class SortOption(val field: SortField, val direction: SortDirection)

enum class VideoViewMode { LIST, GRID }

fun <T : PlayableVideo> List<T>.sortedByOption(option: SortOption): List<T> {
    val comparator = when (option.field) {
        SortField.NAME -> compareBy<T> { it.displayName.lowercase() }
        SortField.DATE -> compareBy<T> { it.dateModifiedMs }
        SortField.SIZE -> compareBy<T> { it.sizeBytes }
    }
    return if (option.direction == SortDirection.DESCENDING) {
        sortedWith(comparator.reversed())
    } else {
        sortedWith(comparator)
    }
}
