package com.tw93.miaoyan.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tw93.miaoyan.android.model.TrashedNote
import com.tw93.miaoyan.android.ui.LibraryUiState
import com.tw93.miaoyan.android.ui.TrashSettingsScreen
import com.tw93.miaoyan.android.ui.theme.MiaoYanTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TrashSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permanentDeleteRequiresNamedConfirmationAndSupportsCancel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val note = trashedNote()
        val deleted = AtomicReference<TrashedNote?>()
        compose.setContent {
            MiaoYanTheme {
                TrashSettingsScreen(
                    state = LibraryUiState(trash = listOf(note)),
                    onRestore = {},
                    onPermanentlyDelete = deleted::set,
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.delete_permanently)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete_permanently_title)).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.delete_permanently_message, note.displayName),
        ).assertIsDisplayed()
        assertNull(deleted.get())
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNull(deleted.get())
    }

    @Test
    fun destructiveConfirmationInvokesDeleteForTheExactRow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val note = trashedNote()
        val deleted = AtomicReference<TrashedNote?>()
        compose.setContent {
            MiaoYanTheme {
                TrashSettingsScreen(
                    state = LibraryUiState(trash = listOf(note)),
                    onRestore = {},
                    onPermanentlyDelete = deleted::set,
                    onBack = {},
                )
            }
        }

        val label = context.getString(R.string.delete_permanently)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithTag("trash-delete-confirm").performClick()
        assertEquals(note, deleted.get())
    }

    private fun trashedNote() = TrashedNote(
        manifestId = "123e4567-e89b-12d3-a456-426614174000",
        trashRelativePath = ".Trash/items/123e4567-e89b-12d3-a456-426614174000/Quarterly plan.md",
        displayName = "Quarterly plan.md",
        originalRelativePath = "Work/Quarterly plan.md",
        deletedAtMillis = 1_000L,
    )
}
