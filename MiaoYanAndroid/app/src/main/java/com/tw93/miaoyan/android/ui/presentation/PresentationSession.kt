package com.tw93.miaoyan.android.ui.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Small saveable navigation state; it retains no Activity, WebView, note, or rendered HTML. */
@Stable
class PresentationSession internal constructor(modeName: String?, slide: Int) {
    private var savedModeName by mutableStateOf(modeName)
    var slide by mutableIntStateOf(slide.coerceAtLeast(0))
        private set

    val mode: PresentationMode?
        get() = savedModeName?.let { name -> PresentationMode.entries.firstOrNull { it.name == name } }

    fun enter(mode: PresentationMode) {
        slide = 0
        savedModeName = mode.name
    }

    fun reportSlide(index: Int) {
        if (index >= 0) slide = index
    }

    fun exit() {
        savedModeName = null
    }

    companion object {
        val Saver = listSaver(
            save = { session -> listOf(session.savedModeName.orEmpty(), session.slide) },
            restore = { saved -> PresentationSession((saved[0] as String).ifEmpty { null }, saved[1] as Int) },
        )
    }
}

@Composable
fun rememberPresentationSession(): PresentationSession =
    rememberSaveable(saver = PresentationSession.Saver) { PresentationSession(modeName = null, slide = 0) }
