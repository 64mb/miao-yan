package com.tw93.miaoyan.android.git

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local signal used to reconcile an existing UI after a background checkout. */
object GitSyncRefreshEvents {
    private val mutableEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()

    internal fun publish() {
        mutableEvents.tryEmit(Unit)
    }
}
