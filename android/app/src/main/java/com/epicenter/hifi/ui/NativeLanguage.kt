package com.epicenter.hifi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalNativeLanguage = compositionLocalOf { "es" }

@Composable
fun nativeText(spanish: String, english: String): String =
    if (LocalNativeLanguage.current == "en") english else spanish
