package com.kzhovn.todoapp.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.kzhovn.todoapp.R

val LedgerTitleFont = FontFamily(
    Font(R.font.pt_serif_regular, FontWeight.Normal),
    Font(R.font.pt_serif_bold, FontWeight.Bold),
)

// Public Sans and JetBrains Mono ship as variable fonts; FontVariation.Settings picks a specific
// weight instance from the single file. Requires API 26+ (this app's minSdk already guarantees it).
@OptIn(ExperimentalTextApi::class)
val LedgerUiFont = FontFamily(
    Font(R.font.public_sans, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)

@OptIn(ExperimentalTextApi::class)
val LedgerMonoFont = FontFamily(
    Font(R.font.jetbrains_mono, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)
