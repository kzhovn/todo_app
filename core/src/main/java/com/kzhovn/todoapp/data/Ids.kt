package com.kzhovn.todoapp.data

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val lastId = AtomicLong(0)

// Ids must be unique across phone and server without coordination, AND sort in creation order:
// the outliner and sequential folders order siblings by id. So: epoch millis in the high bits,
// 11 random bits below, forced monotonic per process. Fits in 53 bits (JS-safe) until ~2109.
// Pre-sync rows keep their small autoincrement ids, which sort before every new one.
fun newId(): Long {
    val candidate = (System.currentTimeMillis() shl 11) or Random.nextLong(2048)
    return lastId.updateAndGet { maxOf(it + 1, candidate) }
}
