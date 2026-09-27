package com.kzhovn.todoapp.focus

import kotlin.random.Random

// Leaving focus mode means typing out a fresh random sentence, so it never becomes muscle memory.
object FocusPhrase {
    private val adjectives = listOf("quiet", "stubborn", "golden", "patient", "sleepy", "curious", "tidy", "restless", "gentle", "clumsy", "brave", "hungry")
    private val animals = listOf("otter", "heron", "badger", "moth", "tortoise", "raven", "hedgehog", "salmon", "beetle", "fox", "walrus", "wren")
    private val verbs = listOf("folds", "counts", "carries", "paints", "hides", "sorts", "buries", "polishes", "mends", "gathers", "trades", "measures")
    private val counts = listOf("three", "four", "five", "six", "seven", "eight", "nine", "eleven", "twelve")
    private val things = listOf("maps", "spoons", "lanterns", "pebbles", "letters", "buttons", "kettles", "ribbons", "acorns", "ladders", "candles", "teacups")
    private val places = listOf("beside the harbor", "under the old bridge", "behind the bakery", "before the rain", "after the long winter", "near the quiet well")

    fun random(random: Random = Random): String =
        "The ${adjectives.random(random)} ${animals.random(random)} ${verbs.random(random)} ${counts.random(random)} ${things.random(random)} ${places.random(random)}."

    // Forgiving about case, spacing and the final period, since a keyboard fights those; every word must match.
    fun matches(phrase: String, typed: String): Boolean = normalize(phrase) == normalize(typed)

    private fun normalize(s: String) = s.lowercase().trim().trimEnd('.').replace(Regex("\\s+"), " ")
}
