package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.atTime
import java.util.Calendar

object QuickAddParser {

    private const val WEEKDAY = "sun(?:day)?|mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?"
    private const val DATE = "today|tomorrow|(?:next\\s+)?(?:$WEEKDAY)|\\d{4}-\\d{1,2}-\\d{1,2}"

    // A time needs am/pm or a colon, so a bare number ("buy 3 apples") is never mistaken for one.
    private const val TIME = "\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}:\\d{2}"

    // A flag takes any token (an unparseable one is dropped); the words "start"/"due" only count
    // when followed by a recognised date or time, so they can still appear in ordinary titles.
    // Either may be followed by an optional time: "-d fri 5pm", "due tomorrow at 9:30".
    private val flagRegex = Regex("(?<!\\S)-([sd])\\s+(\\S+)(?:\\s+(?:at\\s+)?($TIME))?(?!\\S)", RegexOption.IGNORE_CASE)
    private val wordRegex = Regex(
        "(?<!\\S)(start|due)\\s+(?:($DATE)(?:\\s+(?:at\\s+)?($TIME))?|(?:at\\s+)?($TIME))(?!\\S)",
        RegexOption.IGNORE_CASE
    )

    fun parse(input: String): Task {
        var startDate: Long? = null
        var dueDate: Long? = null
        val matches = flagRegex.findAll(input).map { Triple(it.groupValues[1].equals("s", ignoreCase = true), it.groupValues[2], it.groupValues[3]) } +
            wordRegex.findAll(input).map { m ->
                Triple(m.groupValues[1].equals("start", ignoreCase = true), m.groupValues[2], m.groupValues[3].ifEmpty { m.groupValues[4] })
            }
        for ((isStart, dateText, timeText) in matches) { // first mention of each wins
            val date = resolve(dateText, timeText)
            if (isStart) startDate = startDate ?: date else dueDate = dueDate ?: date
        }
        val text = input.replace(flagRegex, "").replace(wordRegex, "").replace(Regex("\\s+"), " ").trim()
        // A "?" ending the title itself (after flags and date phrases are stripped) marks a maybe;
        // one elsewhere, like "update(?) bug", is just part of the title.
        val isMaybe = text.endsWith("?")
        return Task(title = text.removeSuffix("?").trim(), startDate = startDate, dueDate = dueDate, isMaybe = isMaybe)
    }

    // A time alone ("due 5pm", "-d 17:00") means today at that time.
    private fun resolve(dateText: String, timeText: String): Long? {
        val today = Calendar.getInstance().startOfDay()
        val (day, time) = when {
            dateText.isEmpty() -> today to timeText
            else -> parseDate(dateText)?.let { it to timeText }
                ?: parseTime(dateText)?.let { today to dateText }
                ?: return null
        }
        val (hour, minute) = parseTime(time) ?: return day
        return atTime(day, hour, minute)
    }

    private fun parseTime(value: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3].lowercase()) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
        }
        return (hour to minute).takeIf { hour in 0..23 && minute in 0..59 }
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
