package com.tw93.miaoyan.android.data

enum class AppLanguage(val languageTag: String) {
    AUTO_SYSTEM(""),
    ENGLISH("en"),
    RUSSIAN("ru"),
    ;

    companion object {
        fun fromLanguageTags(languageTags: String?): AppLanguage {
            val primaryTag = languageTags.orEmpty().substringBefore(',').trim().lowercase()
            return when {
                primaryTag == "en" || primaryTag.startsWith("en-") -> ENGLISH
                primaryTag == "ru" || primaryTag.startsWith("ru-") -> RUSSIAN
                else -> AUTO_SYSTEM
            }
        }
    }
}
