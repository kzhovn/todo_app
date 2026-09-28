package com.kzhovn.todoapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Every slot Material reads is set, so nothing falls back to its default purple: the drawer, dialogs
// and menus use the surfaceContainer ones, a selected drawer item secondaryContainer.
private val LedgerColorScheme = lightColorScheme(
    background = LedgerBackground,
    surface = LedgerBackground,
    onBackground = LedgerInk,
    onSurface = LedgerInk,
    surfaceVariant = LedgerTile,
    onSurfaceVariant = LedgerMuted,
    surfaceTint = LedgerBackground,
    surfaceBright = LedgerSearchBackground,
    surfaceDim = LedgerTile,
    surfaceContainerLowest = LedgerSearchBackground,
    surfaceContainerLow = LedgerSearchBackground,
    surfaceContainer = LedgerSearchBackground,
    surfaceContainerHigh = LedgerSearchBackground,
    surfaceContainerHighest = LedgerTile,
    primary = LedgerAccent,
    onPrimary = LedgerAccentInk,
    primaryContainer = LedgerAccentSoft,
    onPrimaryContainer = LedgerAccent,
    secondary = LedgerAccent,
    onSecondary = LedgerAccentInk,
    secondaryContainer = LedgerAccentSoft,
    onSecondaryContainer = LedgerAccent,
    tertiary = LedgerAccent,
    onTertiary = LedgerAccentInk,
    tertiaryContainer = LedgerAccentSoft,
    onTertiaryContainer = LedgerAccent,
    outline = LedgerBorder,
    outlineVariant = LedgerBorder,
    scrim = LedgerInk,
)

@Composable
fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColorScheme, content = content)
}
