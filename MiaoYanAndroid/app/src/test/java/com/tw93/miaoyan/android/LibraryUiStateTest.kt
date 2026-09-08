package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.ui.LibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryUiStateTest {
    @Test
    fun pinnedNotesStayFirstWithoutReorderingTheirGroups() {
        val first = note("Notes/First.md")
        val pinnedOne = note("Notes/Pinned One.md")
        val second = note("Notes/Second.md")
        val pinnedTwo = note("Notes/Pinned Two.md")
        val state = LibraryUiState(
            notes = listOf(first, pinnedOne, second, pinnedTwo),
            pinnedPaths = setOf(pinnedOne.relativePath, pinnedTwo.relativePath),
        )

        assertEquals(
            listOf(pinnedOne, pinnedTwo, first, second),
            state.visibleNotes,
        )
    }

    @Test
    fun pinnedSearchResultsStayFirst() {
        val first = note("Notes/First.md")
        val pinned = note("Notes/Pinned.md")
        val state = LibraryUiState(
            query = "note",
            searchResults = listOf(first, pinned),
            pinnedPaths = setOf(pinned.relativePath),
        )

        assertEquals(listOf(pinned, first), state.visibleNotes)
    }

    private fun note(path: String) = LibraryNote(
        id = path,
        relativePath = path,
        displayName = path.substringAfterLast('/'),
        modifiedAtMillis = 1,
        sizeBytes = 1,
    )
}
