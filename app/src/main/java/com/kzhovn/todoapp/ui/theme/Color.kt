package com.kzhovn.todoapp.ui.theme

import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType

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

val LedgerFolderPalette = listOf(
    Color(0xFF8A4C2E),
    Color(0xFF6B8A2E),
    Color(0xFF2E8A4D),
    Color(0xFF2E6B8A),
    Color(0xFF4D2E8A),
    Color(0xFF8A2E6B),
)

// Assigned in folder creation order, so the first palette-size folders always get distinct colors
// (hashing ids let two folders collide).
fun folderColors(tasks: Collection<Task>, palette: List<Color> = LedgerFolderPalette): Map<Long, Color> =
    tasks.filter { it.type == TaskType.FOLDER }.sortedBy { it.id }
        .mapIndexed { i, folder -> folder.id to palette[i % palette.size] }.toMap()
