package com.kzhovn.todoapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LedgerColorScheme = lightColorScheme(
    background = LedgerBackground,
    surface = LedgerBackground,
    onBackground = LedgerInk,
    onSurface = LedgerInk,
    primary = LedgerAccent,
    onPrimary = LedgerAccentInk,
    primaryContainer = LedgerAccentSoft,
    onPrimaryContainer = LedgerAccent,
    outline = LedgerBorder,
)

@Composable
fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColorScheme, content = content)
}
