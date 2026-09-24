package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.Task
import java.util.Calendar

object QuickAddParser {

    private const val WEEKDAY = "sun(?:day)?|mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?"
    private const val DATE = "today|tomorrow|(?:next\\s+)?(?:$WEEKDAY)|\\d{4}-\\d{1,2}-\\d{1,2}"

    // A flag takes any token (an unparseable one is dropped); the words "start"/"due" only count
    // when followed by a recognised date, so they can still appear in ordinary titles.
    private val flagRegex = Regex("(?<!\\S)-([sd])\\s+(\\S+)")
    private val wordRegex = Regex("(?<!\\S)(start|due)\\s+($DATE)(?!\\S)", RegexOption.IGNORE_CASE)

    fun parse(input: String): Task {
        var startDate: Long? = null
        var dueDate: Long? = null
        val matches = flagRegex.findAll(input).map { (it.groupValues[1] == "s") to it.groupValues[2] } +
            wordRegex.findAll(input).map { it.groupValues[1].equals("start", ignoreCase = true) to it.groupValues[2] }
        for ((isStart, value) in matches) { // first mention of each wins
            val date = parseDate(value)
            if (isStart) startDate = startDate ?: date else dueDate = dueDate ?: date
        }
        val text = input.replace(flagRegex, "").replace(wordRegex, "").replace(Regex("\\s+"), " ").trim()
        // A "?" ending the title itself (after flags and date phrases are stripped) marks a maybe;
        // one elsewhere, like "update(?) bug", is just part of the title.
        val isMaybe = text.endsWith("?")
        return Task(title = text.removeSuffix("?").trim(), startDate = startDate, dueDate = dueDate, isMaybe = isMaybe)
    }

    private fun parseDate(value: String): Long? {
        val word = value.lowercase().removePrefix("next").trim()
        val cal = Calendar.getInstance()
        return when {
            word == "today" -> cal.startOfDay()
            word == "tomorrow" -> cal.apply { add(Calendar.DAY_OF_YEAR, 1) }.startOfDay()
            Regex(WEEKDAY).matches(word) -> {
                val target = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat").indexOf(word.take(3)) + 1
                // Always a future day: "due monday" said on a Monday means next week.
                val ahead = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                cal.apply { add(Calendar.DAY_OF_YEAR, if (ahead == 0) 7 else ahead) }.startOfDay()
            }
            else -> runCatching {
                val (year, month, day) = word.split("-").map { it.toInt() }
                Calendar.getInstance().apply { set(year, month - 1, day) }.startOfDay()
            }.getOrNull()
        }
    }
}

fun Calendar.startOfDay(): Long = apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
