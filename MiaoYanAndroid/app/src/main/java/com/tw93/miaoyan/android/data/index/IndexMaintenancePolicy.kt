package com.tw93.miaoyan.android.data.index

/** Physical SQLite measurements used to keep the rebuildable index proportional to its source. */
data class IndexStorageStats(
    val databaseBytes: Long,
    val walBytes: Long,
    val pageSizeBytes: Long,
    val pageCount: Long,
    val freePageCount: Long,
) {
    val totalFileBytes: Long
        get() = saturatedAdd(databaseBytes, walBytes)

    val freeBytes: Long
        get() = saturatedMultiply(pageSizeBytes, freePageCount)
}

/**
 * Conservative maintenance thresholds for a derived FTS cache.
 *
 * A large index is valid when the Markdown corpus itself is large. Rebuilds therefore require
 * either meaningful fragmentation or a file size that is disproportionate to the complete source
 * text. No content is truncated to satisfy these limits.
 */
class IndexMaintenancePolicy(
    private val walCheckpointBytes: Long = 8L * Mebibyte,
    private val minimumRebuildBytes: Long = 32L * Mebibyte,
    private val minimumFreeBytes: Long = 16L * Mebibyte,
    private val fragmentationPercent: Int = 30,
    private val proportionalSlackBytes: Long = 32L * Mebibyte,
    private val maximumSizeMultiple: Long = 4,
) {
    init {
        require(walCheckpointBytes >= 0)
        require(minimumRebuildBytes >= 0)
        require(minimumFreeBytes >= 0)
        require(fragmentationPercent in 0..100)
        require(proportionalSlackBytes >= 0)
        require(maximumSizeMultiple > 0)
    }

    fun shouldCheckpoint(stats: IndexStorageStats): Boolean = stats.walBytes >= walCheckpointBytes

    fun shouldRebuild(stats: IndexStorageStats, sourceTextBytes: Long): Boolean {
        if (stats.totalFileBytes < minimumRebuildBytes) return false

        val fragmented = stats.freeBytes >= minimumFreeBytes &&
            percentageAtLeast(stats.freePageCount, stats.pageCount, fragmentationPercent)
        val proportionalLimit = saturatedAdd(
            proportionalSlackBytes,
            saturatedMultiply(sourceTextBytes.coerceAtLeast(0), maximumSizeMultiple),
        )
        return fragmented || stats.totalFileBytes > proportionalLimit
    }

    companion object {
        private const val Mebibyte = 1024L * 1024L

        fun totalSourceBytes(sizes: Iterable<Long>): Long = sizes.fold(0L) { total, size ->
            saturatedAdd(total, size.coerceAtLeast(0))
        }

        private fun percentageAtLeast(part: Long, whole: Long, percent: Int): Boolean {
            if (whole <= 0) return false
            if (percent == 0) return true
            val threshold = saturatedMultiply(whole, percent.toLong())
            return saturatedMultiply(part, 100) >= threshold
        }
    }
}

private fun saturatedAdd(first: Long, second: Long): Long =
    if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

private fun saturatedMultiply(value: Long, multiplier: Long): Long = when {
    value <= 0 || multiplier <= 0 -> 0
    value > Long.MAX_VALUE / multiplier -> Long.MAX_VALUE
    else -> value * multiplier
}
