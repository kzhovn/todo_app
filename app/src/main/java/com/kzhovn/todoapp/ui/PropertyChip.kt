package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerUiFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared by the quick-add overlay and the full edit screen. Always shows its label (e.g.
// "Start"/"Due") alongside the value, so two chips holding the same-shaped value (two dates, in
// particular) never look identical to each other the way the original quick-add chips did.
@Composable
fun PropertyChip(label: String, valueText: String?, icon: ImageVector, onClick: () -> Unit) {
    val set = valueText != null
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (set) LedgerAccentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (set) LedgerAccent else LedgerMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (set) "$label: $valueText" else label,
            fontFamily = LedgerUiFont,
            fontSize = 12.sp,
            color = if (set) LedgerAccent else LedgerMuted
        )
    }
}

fun formatChipDate(epochMillis: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(epochMillis))
