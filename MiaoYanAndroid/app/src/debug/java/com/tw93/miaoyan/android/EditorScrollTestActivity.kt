package com.tw93.miaoyan.android

import android.os.Bundle
import android.view.Gravity
import androidx.activity.ComponentActivity
import com.tw93.miaoyan.android.ui.MarkdownEditorScrollView

class EditorScrollTestActivity : ComponentActivity() {
    internal lateinit var editorScrollView: MarkdownEditorScrollView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editorScrollView = MarkdownEditorScrollView(this).apply {
            editor.apply {
                gravity = Gravity.TOP or Gravity.START
                textSize = 20f
                setPadding(48, 48, 48, 96)
                setText(
                    buildString {
                        repeat(300) { index ->
                            appendLine("# Section $index")
                            appendLine("A line of Markdown long enough to keep the editor vertically scrollable")
                            appendLine()
                        }
                    },
                )
            }
        }
        setContentView(editorScrollView)
    }
}
