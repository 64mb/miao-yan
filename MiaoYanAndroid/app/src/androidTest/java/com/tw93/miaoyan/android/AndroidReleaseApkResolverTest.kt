package com.tw93.miaoyan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidReleaseApkResolverTest {
    @Test
    fun selectsTheVersionedApkEvenWhenTheOldAliasIsPresent() {
        val release = """
            {
              "tag_name": "V4.2.18",
              "assets": [
                {
                  "name": "MiaoYan-Android.apk",
                  "state": "uploaded",
                  "browser_download_url": "https://github.com/64mb/miao-yan/releases/download/V4.2.18/MiaoYan-Android.apk"
                },
                {
                  "name": "MiaoYan-Android-V4.2.18.apk",
                  "state": "uploaded",
                  "browser_download_url": "https://github.com/64mb/miao-yan/releases/download/V4.2.18/MiaoYan-Android-V4.2.18.apk"
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            VersionedApk(
                "MiaoYan-Android-V4.2.18.apk",
                "https://github.com/64mb/miao-yan/releases/download/V4.2.18/MiaoYan-Android-V4.2.18.apk",
            ),
            AndroidReleaseApkResolver.parseLatest(release),
        )
    }

    @Test
    fun failsWhenLatestReleaseHasNoMatchingVersionedApk() {
        val release = """
            {
              "tag_name": "V4.2.18",
              "assets": [
                {
                  "name": "MiaoYan-Android-V4.2.17.apk",
                  "state": "uploaded",
                  "browser_download_url": "https://github.com/64mb/miao-yan/releases/download/V4.2.17/MiaoYan-Android-V4.2.17.apk"
                }
              ]
            }
        """.trimIndent()

        assertThrows(IOException::class.java) {
            AndroidReleaseApkResolver.parseLatest(release)
        }
    }

    @Test
    fun rejectsAnApkUrlThatDoesNotBelongToTheLatestTag() {
        val release = """
            {
              "tag_name": "V4.2.18",
              "assets": [
                {
                  "name": "MiaoYan-Android-V4.2.18.apk",
                  "state": "uploaded",
                  "browser_download_url": "https://github.com/64mb/miao-yan/releases/download/V4.2.17/MiaoYan-Android-V4.2.18.apk"
                }
              ]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            AndroidReleaseApkResolver.parseLatest(release)
        }
    }
}
