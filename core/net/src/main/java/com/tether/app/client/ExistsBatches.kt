package com.tether.app.client

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * The request shape of `POST /api/files/exists` (the web's `lib/mention-existence.ts`): 1..[MAX_PATHS] non-empty
 * strings, the whole JSON body at most [MAX_BODY] UTF-16 characters. A load is split into several requests; a path
 * that alone would break the limit is never sent.
 */
object ExistsBatches {
    const val MAX_PATHS = 200
    const val MAX_BODY = 4096

    /** Order is kept; each sendable path lands in exactly one chunk. */
    class Split(val chunks: List<List<String>>, val unsendable: List<String>)

    /** The JSON body of one request. */
    fun body(paths: List<String>): String = buildJsonObject {
        put("paths", buildJsonArray { paths.forEach { add(it) } })
    }.toString()

    fun split(paths: List<String>): Split {
        val chunks = ArrayList<List<String>>()
        val unsendable = ArrayList<String>()
        val overhead = body(emptyList()).length // {"paths":[]}
        var current = ArrayList<String>()
        var size = overhead
        for (path in paths) {
            val cost = JsonPrimitive(path).toString().length
            if (path.isEmpty() || overhead + cost > MAX_BODY) {
                unsendable += path
                continue
            }
            val added = if (current.isEmpty()) cost else cost + 1
            if (current.size >= MAX_PATHS || size + added > MAX_BODY) {
                chunks += current
                current = ArrayList()
                size = overhead
            }
            size += if (current.isEmpty()) cost else cost + 1
            current += path
        }
        if (current.isNotEmpty()) chunks += current
        return Split(chunks, unsendable)
    }
}
