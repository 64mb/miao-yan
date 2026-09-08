package com.tw93.miaoyan.android.data.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared access boundary for the canonical library.
 *
 * Git and attachment coordinators must use this same contract before inspecting or mutating the
 * app-private tree. Reads that require a consistent filesystem snapshot are exclusive as well.
 */
interface LibraryAccess {
    suspend fun <T> withExclusiveAccess(operation: suspend () -> T): T
}

/** The single process-wide gate for filesDir/libraries/default. */
object LibraryMutationGate : LibraryAccess {
    private val mutex = Mutex()

    override suspend fun <T> withExclusiveAccess(operation: suspend () -> T): T =
        mutex.withLock { operation() }
}
