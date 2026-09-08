package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.index.IndexMaintenancePolicy
import com.tw93.miaoyan.android.data.index.IndexStorageStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexMaintenancePolicyTest {
    private val policy = IndexMaintenancePolicy()

    @Test
    fun checkpointsOnlyWhenWalCrossesBound() {
        assertFalse(policy.shouldCheckpoint(stats(walBytes = 8 * Mebibyte - 1)))
        assertTrue(policy.shouldCheckpoint(stats(walBytes = 8 * Mebibyte)))
    }

    @Test
    fun ignoresSmallDatabaseEvenWhenItsPercentageLooksFragmented() {
        val small = stats(
            databaseBytes = 4 * Mebibyte,
            pageCount = 1_000,
            freePageCount = 900,
        )

        assertFalse(policy.shouldRebuild(small, sourceTextBytes = 1))
    }

    @Test
    fun rebuildsMeaningfullyFragmentedDatabase() {
        val fragmented = stats(
            databaseBytes = 64 * Mebibyte,
            pageCount = 16_384,
            freePageCount = 8_192,
        )

        assertTrue(policy.shouldRebuild(fragmented, sourceTextBytes = 32 * Mebibyte))
    }

    @Test
    fun rebuildsDisproportionateHighWaterButKeepsLargeValidCorpus() {
        val database = stats(databaseBytes = 160 * Mebibyte)

        assertTrue(policy.shouldRebuild(database, sourceTextBytes = 16 * Mebibyte))
        assertFalse(policy.shouldRebuild(database, sourceTextBytes = 64 * Mebibyte))
    }

    @Test
    fun sourceByteAccountingSaturatesInsteadOfOverflowingIntoFalseRebuilds() {
        val total = IndexMaintenancePolicy.totalSourceBytes(listOf(Long.MAX_VALUE, Long.MAX_VALUE, -1))

        assertTrue(total == Long.MAX_VALUE)
        assertFalse(policy.shouldRebuild(stats(databaseBytes = 64 * Mebibyte), total))
    }

    private fun stats(
        databaseBytes: Long = 1 * Mebibyte,
        walBytes: Long = 0,
        pageCount: Long = databaseBytes / PageSize,
        freePageCount: Long = 0,
    ) = IndexStorageStats(
        databaseBytes = databaseBytes,
        walBytes = walBytes,
        pageSizeBytes = PageSize,
        pageCount = pageCount,
        freePageCount = freePageCount,
    )

    private companion object {
        const val Mebibyte = 1024L * 1024L
        const val PageSize = 4_096L
    }
}
