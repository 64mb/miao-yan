package com.tw93.miaoyan.android.data

import android.content.Context
import java.io.File

class AndroidDemoSeedSource(context: Context) : DemoSeedSource {
    private val assets = context.assets

    override fun open(assetName: String) = assets.open(assetName)
}

fun Context.demoLibrarySeeder(): DemoLibrarySeeder = DemoLibrarySeeder(
    libraryRoot = File(filesDir, "libraries/default"),
    stateFile = File(noBackupFilesDir, "library-bootstrap/default-demo.state"),
    source = AndroidDemoSeedSource(this),
)

fun Context.preferredLanguageTags(): List<String> =
    resources.configuration.locales.let { locales ->
        buildList {
            for (index in 0 until locales.size()) add(locales[index].toLanguageTag())
        }
    }
