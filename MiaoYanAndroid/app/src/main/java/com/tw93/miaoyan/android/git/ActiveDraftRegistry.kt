package com.tw93.miaoyan.android.git

object ActiveDraftRegistry {
    @Volatile
    private var state = DraftState(null, false)

    fun update(ownerPath: String?, dirty: Boolean) {
        state = DraftState(ownerPath, dirty)
    }

    fun requireSafeForCheckout() {
        val current = state
        if (current.dirty) {
            throw GitSyncException.Storage(
                "Remote files were not applied because ${current.ownerPath ?: "the active note"} has an unsaved draft.",
            )
        }
    }

    private data class DraftState(val ownerPath: String?, val dirty: Boolean)
}
