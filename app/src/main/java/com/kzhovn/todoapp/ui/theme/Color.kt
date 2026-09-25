package com.kzhovn.todoapp.ui.theme

import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.folderColorsArgb

val LedgerBackground = Color(0xFFF0EAE0)
val LedgerSearchBackground = Color(0xFFF7F4EE)
val LedgerInk = Color(0xFF2B2318)
val LedgerMuted = Color(0xFF8A7A5C)
val LedgerBorder = Color(0xFFDED5C1)

val LedgerAccent = Color(0xFF2B4C7E)
val LedgerAccentInk = Color(0xFFFFFFFF)
val LedgerAccentSoft = Color(0xFFDFE6EF)

val LedgerStar = Color(0xFFB8862F)

val LedgerToday = Color(0xFF93650C)
val LedgerTodayBg = Color(0xFFEEDFB8)
val LedgerOverdue = Color(0xFF96412B)
val LedgerOverdueBg = Color(0xFFEEDACE)
val LedgerNeutralBg = Color(0xFFE9E2D2)

val LedgerCheckBorder = Color(0xFFB5A98C)

// Families of colours per top-level folder; see folderColorsArgb in :core (shared with the web).
fun folderColors(tasks: Collection<Task>): Map<Long, Color> = folderColorsArgb(tasks).mapValues { Color(it.value) }
