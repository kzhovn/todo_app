package com.kzhovn.todoapp.data

internal val URL = Regex("https?://\\S+")

// A note's text split into plain runs and links (trailing punctuation isn't part of a link), for each
// client to render: (text, isLink).
fun linkSegments(text: String): List<Pair<String, Boolean>> = buildList {
    var at = 0
    URL.findAll(text).forEach { m ->
        val url = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
        add(text.substring(at, m.range.first) to false)
        add(url to true)
        at = m.range.first + url.length
    }
    add(text.substring(at) to false)
}
