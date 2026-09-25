package com.kzhovn.todoapp.data

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Folder colours, shared by the app, its widget and the web. Each top-level folder gets its own
// colour; folders inside it get small variations of that colour, so a folder tree reads as one
// family. Where they conflict, keeping families apart wins over telling siblings apart.
//
// Colours are in OKLCH (perceptual lightness, chroma, hue), so equal steps look equal.
private data class Oklch(val l: Double, val c: Double, val h: Double)

// Top-level colours, by slot: a new folder takes the first free one, so the first three (the furthest
// apart) are what a few folders get. Lightness varies as well as hue to separate them, within one
// earthy saturation. Every pair clears ΔE 15 (OKLab ×100), checked with the dataviz validator.
private val BASES = listOf(
    Oklch(0.56, 0.13, 145.0), // green  #3B8841
    Oklch(0.56, 0.14, 35.0),  // rust   #B75037
    Oklch(0.48, 0.13, 262.0), // blue   #335AA6
    Oklch(0.72, 0.13, 88.0),  // ochre  #C69F32
    Oklch(0.42, 0.12, 335.0), // plum   #732F67
    Oklch(0.70, 0.09, 215.0), // sky    #54ADC1
)

// A folder inside a family: lighter or darker, with at most 8° of hue drift. Top-level hues are
// ≥47° apart, so families never cross.
private val VARIANTS = listOf(
    0.10 to 6.0, -0.09 to -6.0, 0.17 to -3.0, -0.15 to 4.0,
    0.05 to -8.0, 0.22 to 3.0, -0.04 to 8.0, -0.20 to -4.0,
)

/**
 * Folders whose colour slot is missing, or clashes with another folder's in the same family (the top
 * level is one family; each top-level folder's descendants another), copied with the first free slot.
 * Callers store these, so a folder keeps its colour for good; the older folder keeps a clashing slot.
 * Unassigned folders are filled in the All tree's order, so a first run colours top to bottom.
 */
fun folderColorAssignments(tasks: Collection<Task>): List<Task> {
    val byId = tasks.associateBy { it.id }
    val families = tasks.filter { it.type == TaskType.FOLDER }.groupBy { outermostFolder(it, byId)?.id }
    return families.values.flatMap { members ->
        val taken = mutableSetOf<Int>()
        val needs = members.sortedBy { it.id }.filter { f -> f.colorIndex?.let { !taken.add(it) } ?: true }
        needs.sortedWith(TaskOrder).map { f ->
            val slot = generateSequence(0) { it + 1 }.first { it !in taken }
            taken += slot
            f.copy(colorIndex = slot)
        }
    }
}

/** ARGB colour for every folder in [tasks], keyed by folder id, from their colour slots. */
fun folderColorsArgb(tasks: Collection<Task>): Map<Long, Int> {
    // Slots not yet stored are filled in here the same way, so colours are right before they're saved.
    val assigned = folderColorAssignments(tasks).associateBy { it.id }
    val byId = tasks.associateBy { it.id }.mapValues { (id, t) -> assigned[id] ?: t }
    fun base(slot: Int) = BASES[slot % BASES.size].let { it.copy(l = it.l - 0.08 * (slot / BASES.size)) } // past the palette: a step darker, not a new hue
    return byId.values.filter { it.type == TaskType.FOLDER }.associate { folder ->
        val slot = folder.colorIndex ?: 0
        val top = outermostFolder(folder, byId)
        folder.id to if (top == null) argb(base(slot)) else {
            val family = base(top.colorIndex ?: 0)
            val (dl, dh) = VARIANTS[slot % VARIANTS.size]
            argb(family.copy(l = (family.l + dl).coerceIn(0.30, 0.85), h = family.h + dh))
        }
    }
}

private fun argb(color: Oklch): Int {
    val a = color.c * cos(Math.toRadians(color.h))
    val b = color.c * sin(Math.toRadians(color.h))
    val l = (color.l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (color.l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (color.l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    fun channel(linear: Double): Int {
        val x = linear.coerceIn(0.0, 1.0) // out-of-gamut channels clip
        val encoded = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
        return (encoded * 255).roundToInt()
    }
    val r = channel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
    val g = channel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
    val bl = channel(-0.0041960863 * l - 0.7034186147 * m + 1.7076146010 * s)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}
