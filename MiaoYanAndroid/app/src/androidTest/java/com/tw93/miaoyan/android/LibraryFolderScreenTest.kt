package com.tw93.miaoyan.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tw93.miaoyan.android.model.LibraryFolder
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.ui.LibraryScreen
import com.tw93.miaoyan.android.ui.LibraryUiState
import com.tw93.miaoyan.android.ui.theme.MiaoYanTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryFolderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun foldersRenderBeforeNotesAndOpenForNavigation() {
        val opened = AtomicReference<LibraryFolder?>()
        val folder = folder("Projects")
        showLibrary(
            state = LibraryUiState(
                folders = listOf(folder),
                notes = listOf(note("Root.md")),
            ),
            onOpenFolder = opened::set,
        )

        compose.onNodeWithTag("folder-row:Projects").assertIsDisplayed()
        compose.onNodeWithTag("note-row:Root.md").assertIsDisplayed()
        assertTrue(
            compose.onNodeWithTag("folder-row:Projects").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("note-row:Root.md").fetchSemanticsNode().boundsInRoot.top,
        )
        compose.onNodeWithText("Projects").performClick()
        assertEquals(folder, opened.get())
    }

    @Test
    fun breadcrumbBackAndFolderCreationDispatchExactPathsAndNames() {
        val openedPath = AtomicReference<String?>()
        val created = AtomicReference<String?>()
        val navigatedUp = AtomicReference(false)
        showLibrary(
            state = LibraryUiState(currentFolder = folder("Projects/2026")),
            onOpenFolderPath = openedPath::set,
            onNavigateUp = { navigatedUp.set(true) },
            onCreateFolder = created::set,
        )

        compose.onNodeWithTag("folder-back").performClick()
        assertEquals(true, navigatedUp.get())
        compose.onNodeWithText("Projects").performClick()
        assertEquals("Projects", openedPath.get())

        compose.onNodeWithTag("create-folder").performClick()
        compose.onNodeWithTag("folder-name").performTextInput("Drafts")
        compose.onNodeWithTag("folder-name-confirm").performClick()
        assertEquals("Drafts", created.get())
    }

    @Test
    fun breadcrumbBackUsesAStandardCenteredTouchTarget() {
        showLibrary(state = LibraryUiState(currentFolder = folder("Projects")))

        compose.onNodeWithTag("folder-back")
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun folderRenameAndTrashRequireExplicitActions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder = folder("Projects")
        val renamed = AtomicReference<Pair<LibraryFolder, String>?>()
        val trashed = AtomicReference<LibraryFolder?>()
        showLibrary(
            state = LibraryUiState(folders = listOf(folder)),
            onRenameFolder = { item, name -> renamed.set(item to name) },
            onMoveFolderToTrash = trashed::set,
        )

        compose.onNodeWithTag("folder-actions:Projects").performClick()
        compose.onNodeWithText(context.getString(R.string.rename)).performClick()
        compose.onNodeWithTag("folder-name").performTextClearance()
        compose.onNodeWithTag("folder-name").performTextInput("Archive")
        compose.onNodeWithTag("folder-name-confirm").performClick()
        assertEquals(folder to "Archive", renamed.get())

        compose.onNodeWithTag("folder-actions:Projects").performClick()
        compose.onNodeWithText(context.getString(R.string.trash)).performClick()
        compose.onNodeWithText(context.getString(R.string.move_folder_to_trash_title)).assertIsDisplayed()
        assertNull(trashed.get())
        compose.onNodeWithTag("folder-trash-confirm").performClick()
        assertEquals(folder, trashed.get())
    }

    private fun showLibrary(
        state: LibraryUiState,
        onOpenFolder: (LibraryFolder) -> Unit = {},
        onOpenFolderPath: (String) -> Unit = {},
        onNavigateUp: () -> Unit = {},
        onCreateFolder: (String) -> Unit = {},
        onRenameFolder: (LibraryFolder, String) -> Unit = { _, _ -> },
        onMoveFolderToTrash: (LibraryFolder) -> Unit = {},
    ) {
        compose.setContent {
            MiaoYanTheme {
                LibraryScreen(
                    state = state,
                    onRefresh = {},
                    onQueryChanged = {},
                    onOpenNote = {},
                    onOpenFolder = onOpenFolder,
                    onOpenFolderPath = onOpenFolderPath,
                    onNavigateUp = onNavigateUp,
                    onCreateNote = {},
                    onCreateFolder = onCreateFolder,
                    onRenameNote = { _, _ -> },
                    onRenameFolder = onRenameFolder,
                    onMoveToTrash = {},
                    onMoveFolderToTrash = onMoveFolderToTrash,
                    onTogglePinned = {},
                    onResolveConflict = {},
                    onSettings = {},
                )
            }
        }
    }

    private fun folder(path: String) = LibraryFolder(path, path, path.substringAfterLast('/'))

    private fun note(path: String) = LibraryNote(path, path, path.substringAfterLast('/'), 1, 0)
}
