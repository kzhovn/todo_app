package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.atTime
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit
import com.kzhovn.todoapp.recurrence.WEEKDAYS_MASK
import com.kzhovn.todoapp.recurrence.toTaskFields
import java.util.Calendar

// Everything a quick add asks for. `task` is null when the text only adds items to an existing
// checklist ("groceries: milk, eggs"); otherwise `items` are the new checklist's ("packing [a, b]").
data class QuickAdd(
    val task: Task?,
    val items: List<String> = emptyList(),
    val intoChecklist: Long? = null,
    val contextIds: Set<Long> = emptySet(),
    val pin: Boolean = false,
    val focus: Boolean = false
) {
    val isEmpty: Boolean get() = task?.title?.isBlank() ?: items.isEmpty()
}

// The syntax that needs no names (see planQuickAdd for "work:", "d:" and "@home").
object QuickAddParser {
    private val I = RegexOption.IGNORE_CASE
    private const val WEEKDAY = "sun(?:day)?|mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?"
    private const val MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val DAY_OF_MONTH = "\\d{1,2}(?:st|nd|rd|th)?"
    // "+3d", "in 2 weeks", "in two days"; hours and minutes ("+2h", "in four hours", "in half an hour",
    // "in 30 minutes") count from now, the rest from today. Numbers may be spelled out, as said aloud.
    private const val COUNT = "\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val OFFSET = "\\+\\d+[dwmh]|in\\s+half\\s+an?\\s+hour|in\\s+(?:$COUNT)\\s+(?:days?|weeks?|months?|hours?|minutes?|mins?)"
    private const val DATE = "today|tomorrow|next\\s+week|(?:this\\s+)?weekend|(?:next\\s+)?(?:$WEEKDAY)|$OFFSET|" +
        "\\d{4}-\\d{1,2}-\\d{1,2}|(?:$MONTH)\\s+$DAY_OF_MONTH|$DAY_OF_MONTH\\s+(?:$MONTH)"

    // A time needs am/pm or a colon, so a bare number ("buy 3 apples") is never mistaken for one.
    private const val TIME = "\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}:\\d{2}"

    // A flag takes a date (which may be several words) or any one token (an unparseable one is
    // dropped); the words "start"/"due" only count when followed by a recognised date or time, so they
    // can still appear in ordinary titles. Either may be followed by a time: "-d fri 5pm", "due tomorrow at 9:30".
    private val flagRegex = Regex("(?<!\\S)-([sd])\\s+($DATE|\\S+)(?:\\s+(?:at\\s+)?($TIME))?(?!\\S)", I)
    private val wordRegex = Regex("(?<!\\S)(start|due)\\s+(?:($DATE)(?:\\s+(?:at\\s+)?($TIME))?|(?:at\\s+)?($TIME))(?!\\S)", I)

    // Repeats: "every 4 days after done" (from completion), else a schedule: "every day", "every 2 weeks",
    // "every weekday", "every 1st sat", "every mon, thu".
    private const val UNIT = "days?|weeks?|months?"
    private const val NTH = "1st|2nd|3rd|4th|first|second|third|fourth|last"
    private val afterDoneRegex = Regex("(?<!\\S)every\\s+(?:(\\d+)\\s+)?($UNIT)\\s+after\\s+(?:done|completion)(?!\\S)", I)
    private val everyRegex = Regex(
        "(?<!\\S)every\\s+(?:(?:(\\d+)\\s+)?($UNIT)|(weekday)|($NTH)\\s+($WEEKDAY)|((?:$WEEKDAY)(?:\\s*(?:,|and|&)\\s*(?:$WEEKDAY))*))(?!\\S)", I
    )
    private val remindRegex = Regex("(?<!\\S)remind(?:\\s+me)?\\s+(\\d+)\\s*(m|mins?|minutes?|h|hrs?|hours?|d|days?)(?:\\s+before)?(?!\\S)", I)
    private val pinRegex = Regex("(?<!\\S)-([pf])(?!\\S)", I)
    // "~30m", "~1h", "~1h30m", "~1.5h": a timed task, anywhere in the text.
    private val tildeRegex = Regex("(?<!\\S)~(?:(\\d+(?:\\.\\d+)?)h)?\\s?(?:(\\d+)m)?(?!\\S)", I)
    private val itemsRegex = Regex("^(.*?)\\s*\\[([^\\[\\]]*)]$")

    // A timed task leads with its duration and "of": "1 hour of ticket work", "30 minutes of research",
    // "an hour of", "1.5 hours of", "1h 30m of". "of" is required, so a title that merely starts with a
    // duration ("2 hours drive to Bath") isn't read as a timer.
    private const val HOURS = "hours?|hrs?|h"
    private const val MINUTES = "minutes?|mins?|m"
    private val durationRegex = Regex(
        "^(?:(\\d+(?:\\.\\d+)?|an?|half an?)\\s*(?:$HOURS)(?:\\s*(?:and\\s+)?(\\d+)\\s*(?:$MINUTES))?|(\\d+)\\s*(?:$MINUTES))\\s+of\\s+(.+)$", I
    )

    fun parse(input: String, now: Long = System.currentTimeMillis()): Task = read(input, now).task!!

    // The note: everything after the first line, or after " // " on it ("call bank // ask about the
    // fee"). A URL's "//" never counts, since it follows ":" rather than a space.
    fun splitNotes(input: String): Pair<String, String?> {
        val lines = input.trim().lines()
        val first = lines.first()
        val slash = Regex("(^|\\s)//(\\s|$)").find(first)
        val title = if (slash == null) first else first.substring(0, slash.range.first)
        val notes = listOfNotNull(slash?.let { first.substring(it.range.last + 1) }) + lines.drop(1)
        return title to notes.joinToString("\n").trim().ifEmpty { null }
    }

    fun read(input: String, now: Long = System.currentTimeMillis()): QuickAdd {
        val (titlePart, notes) = splitNotes(input)
        return readTitle(titlePart, now).let { it.copy(task = it.task!!.copy(notes = notes)) }
    }

    private fun readTitle(input: String, now: Long): QuickAdd {
        var startDate: Long? = null
        var dueDate: Long? = null
        val matches = flagRegex.findAll(input).map { Triple(it.groupValues[1].equals("s", ignoreCase = true), it.groupValues[2], it.groupValues[3]) } +
            wordRegex.findAll(input).map { m ->
                Triple(m.groupValues[1].equals("start", ignoreCase = true), m.groupValues[2], m.groupValues[3].ifEmpty { m.groupValues[4] })
            }
        for ((isStart, dateText, timeText) in matches) { // first mention of each wins
            val date = resolve(dateText, timeText, now)
            if (isStart) startDate = startDate ?: date else dueDate = dueDate ?: date
        }
        var text = input.replace(flagRegex, "").replace(wordRegex, "")

        val repeat = afterDoneRegex.find(text)?.let { m ->
            text = text.removeRange(m.range)
            RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = m.groupValues[1].ifEmpty { "1" }.toInt(), unit = unit(m.groupValues[2]))
        } ?: everyRegex.find(text)?.let { m ->
            text = text.removeRange(m.range)
            schedule(m.groupValues)
        }
        val remind = remindRegex.find(text)?.let { m ->
            text = text.removeRange(m.range)
            val n = m.groupValues[1].toInt()
            when (m.groupValues[2].lowercase().first()) { 'h' -> n * 60; 'd' -> n * 24 * 60; else -> n }
        }
        val flags = pinRegex.findAll(text).map { it.groupValues[1].lowercase() }.toSet()
        text = text.replace(pinRegex, "")
        text = text.replace(Regex("\\s+"), " ").trim()
        // A "?" or "*" ending the title itself (after the flags and phrases above are stripped) marks a
        // maybe or a star; one elsewhere, like "update(?) bug", is just part of the title. A maybe is
        // never starred.
        var isMaybe = false
        var isStarred = false
        while (text.endsWith("?") || text.endsWith("*")) {
            if (text.endsWith("?")) isMaybe = true else isStarred = true
            text = text.dropLast(1).trimEnd()
        }
        var minutes = tildeRegex.find(text)?.takeIf { it.groupValues[1].isNotEmpty() || it.groupValues[2].isNotEmpty() }?.let { m ->
            text = text.removeRange(m.range).replace(Regex("\\s+"), " ").trim()
            ((m.groupValues[1].toDoubleOrNull() ?: 0.0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)).toInt().takeIf { it > 0 }
        }

        val items = itemsRegex.matchEntire(text)?.let { m -> text = m.groupValues[1]; splitItems(m.groupValues[2]) }
        durationRegex.matchEntire(text)?.let { m ->
            val hours = when (val h = m.groupValues[1].lowercase()) {
                "" -> 0.0
                "a", "an" -> 1.0
                "half a", "half an" -> 0.5
                else -> h.toDouble()
            }
            val of = (hours * 60 + (m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { "0" }).toInt()).toInt()
            if (of > 0) { minutes = minutes ?: of; text = m.groupValues[4].trim() }
        }

        val (recurrenceType, recurrenceRule) = repeat?.toTaskFields() ?: (null to null)
        // A schedule with no start given starts on its first day: "every mon" typed on a Wednesday
        // shows up on Monday.
        if (recurrenceType == RecurrenceType.RRULE && startDate == null) startDate = firstOccurrence(recurrenceRule!!, now)
        val task = Task(
            title = text,
            type = if (items != null) TaskType.CHECKLIST else TaskType.TASK,
            startDate = startDate,
            dueDate = dueDate,
            isMaybe = isMaybe,
            isStarred = isStarred && !isMaybe,
            durationMinutes = minutes,
            recurrenceType = recurrenceType,
            recurrenceRule = recurrenceRule,
            // A reminder is timed from the due date, so without one it has nothing to go by.
            reminderOffsetMinutes = remind?.takeIf { dueDate != null }
        )
        return QuickAdd(task, items = items.orEmpty(), pin = "p" in flags, focus = "f" in flags)
    }

    private fun unit(text: String) = when (text.lowercase().first()) { 'w' -> RecurrenceUnit.WEEK; 'm' -> RecurrenceUnit.MONTH; else -> RecurrenceUnit.DAY }

    // everyRegex's groups: 1-2 "[n] days", 3 "weekday", 4-5 "1st sat", 6 "mon, thu".
    private fun schedule(g: List<String>): RecurrenceSelection = when {
        g[2].isNotEmpty() -> RecurrenceSelection(RecurrencePreset.CALENDAR, n = g[1].ifEmpty { "1" }.toInt(), unit = unit(g[2]))
        g[3].isNotEmpty() -> RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK, weekdaysMask = WEEKDAYS_MASK)
        g[4].isNotEmpty() -> RecurrenceSelection(
            RecurrencePreset.CALENDAR, unit = RecurrenceUnit.MONTH,
            monthlyNth = when (g[4].lowercase().take(2)) { "1s", "fi" -> 1; "2n", "se" -> 2; "3r", "th" -> 3; "4t", "fo" -> 4; else -> -1 },
            monthlyWeekday = weekdayIndex(g[5])
        )
        else -> RecurrenceSelection(
            RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK,
            weekdaysMask = Regex(WEEKDAY, I).findAll(g[6]).fold(0) { mask, d -> mask or (1 shl weekdayIndex(d.value)) }
        )
    }

    // Su=0 .. Sa=6, as RecurrenceSelection counts them.
    private fun weekdayIndex(day: String) = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat").indexOf(day.lowercase().take(3))

    private fun firstOccurrence(rule: String, now: Long): Long {
        val today = Calendar.getInstance().apply { timeInMillis = now }.startOfDay()
        // Only a rule naming weekdays can skip today; any other one lands on its start.
        if ("BYDAY" !in rule) return today
        val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        return RecurrenceEngine.preview(RecurrenceType.RRULE, rule, yesterday, today, count = 1).firstOrNull() ?: today
    }

    // A time alone ("due 5pm", "-d 17:00") means today at that time.
    private fun resolve(dateText: String, timeText: String, now: Long): Long? {
        minutesFromNow(dateText)?.let { return now + it * 60_000L }
        val today = Calendar.getInstance().apply { timeInMillis = now }.startOfDay()
        val (day, time) = when {
            dateText.isEmpty() -> today to timeText
            else -> parseDate(dateText, now)?.let { it to timeText }
                ?: parseTime(dateText)?.let { today to dateText }
                ?: return null
        }
        val (hour, minute) = parseTime(time) ?: return day
        return atTime(day, hour, minute)
    }

    private fun minutesFromNow(value: String): Int? {
        val v = value.trim().lowercase().replace(Regex("\\s+"), " ")
        if (v == "in half an hour" || v == "in half a hour") return 30
        val m = Regex("\\+(\\d+)h|in ($COUNT) (hours?|minutes?|mins?)").matchEntire(v) ?: return null
        if (m.groupValues[1].isNotEmpty()) return m.groupValues[1].toInt() * 60
        val n = count(m.groupValues[2])
        return if (m.groupValues[3].startsWith("h")) n * 60 else n
    }

    // "4", "four", "a"/"an": how many.
    private fun count(word: String): Int = word.toIntOrNull() ?: when (word) {
        "a", "an" -> 1
        else -> listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").indexOf(word) + 1
    }

    private fun parseTime(value: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", I).matchEntire(value.trim()) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3].lowercase()) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
        }
        return (hour to minute).takeIf { hour in 0..23 && minute in 0..59 }
    }

    private fun parseDate(value: String, now: Long): Long? {
        val word = value.lowercase().replace(Regex("\\s+"), " ").trim()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        // Always a future day: "due monday" said on a Monday means next week.
        fun ahead(target: Int) = cal.apply { add(Calendar.DAY_OF_YEAR, ((target - dow + 7) % 7).takeIf { it != 0 } ?: 7) }.startOfDay()
        val offset = Regex("\\+(\\d+)([dwm])|in ($COUNT) (day|week|month)s?").matchEntire(word)
        val monthDay = Regex("(?:([a-z]+) (\\d{1,2})|(\\d{1,2}) ([a-z]+))").matchEntire(word.replace(Regex("(\\d)(st|nd|rd|th)"), "$1"))
        return when {
            word == "today" -> cal.startOfDay()
            word == "tomorrow" -> cal.apply { add(Calendar.DAY_OF_YEAR, 1) }.startOfDay()
            word == "next week" -> ahead(Calendar.MONDAY)
            // Today if it's already the weekend.
            word.endsWith("weekend") -> if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) cal.startOfDay() else ahead(Calendar.SATURDAY)
            Regex(WEEKDAY).matches(word.removePrefix("next ")) -> ahead(weekdayIndex(word.removePrefix("next ")) + 1)
            offset != null -> {
                val n = count(offset.groupValues[1].ifEmpty { offset.groupValues[3] })
                val field = when (offset.groupValues[2].ifEmpty { offset.groupValues[4] }.first()) {
                    'w' -> Calendar.WEEK_OF_YEAR
                    'm' -> Calendar.MONTH
                    else -> Calendar.DAY_OF_YEAR
                }
                cal.apply { add(field, n) }.startOfDay()
            }
            monthDay != null -> {
                val month = MONTHS.indexOf(monthDay.groupValues[1].ifEmpty { monthDay.groupValues[4] }.take(3)).takeIf { it >= 0 } ?: return null
                val day = monthDay.groupValues[2].ifEmpty { monthDay.groupValues[3] }.toInt()
                // The next such date: "oct 12" in November is next year's.
                val date = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day) }.startOfDay()
                if (date < cal.startOfDay()) Calendar.getInstance().apply { timeInMillis = date; add(Calendar.YEAR, 1) }.timeInMillis else date
            }
            else -> runCatching {
                val (year, month, day) = word.split("-").map { it.toInt() }
                Calendar.getInstance().apply { set(year, month - 1, day) }.startOfDay()
            }.getOrNull()
        }
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}

fun Calendar.startOfDay(): Long = apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
