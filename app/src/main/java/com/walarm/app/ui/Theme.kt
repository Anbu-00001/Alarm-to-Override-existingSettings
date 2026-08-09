package com.walarm.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The app's palette. These values were previously inline hex literals repeated in
 * MainActivity (twice), the dashboard chrome and the individual screens, so a colour
 * tweak meant hunting for magic numbers.
 */
object ZAlarmColors {
    val Background = Color(0xFF0C091A)
    val Surface = Color(0xFF130D2B)
    val Primary = Color(0xFF985EFF)
    val Secondary = Color(0xFF007AFF)
}

@Composable
fun ZAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ZAlarmColors.Background,
            surface = ZAlarmColors.Surface,
            primary = ZAlarmColors.Primary,
            secondary = ZAlarmColors.Secondary
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = ZAlarmColors.Background,
            content = content
        )
    }
}
