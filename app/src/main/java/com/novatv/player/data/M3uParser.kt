package com.novatv.player.data

import java.io.BufferedReader

class M3uResult(val channels: List<Channel>, val epgUrl: String)

/** Streaming M3U / M3U8 (extended) playlist parser. */
object M3uParser {
    private val attrRegex = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")

    fun parse(reader: BufferedReader): M3uResult {
        val out = ArrayList<Channel>()
        val ids = HashSet<String>()
        var epgUrl = ""
        var attrs: Map<String, String> = emptyMap()
        var title = ""
        var extGroup = ""
        var ua = ""
        var ref = ""

        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val a = attributes(line)
                        epgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"] ?: "")
                            .split(',').first().trim()
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val comma = titleComma(line)
                        attrs = attributes(if (comma >= 0) line.substring(0, comma) else line)
                        title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        extGroup = ""; ua = ""; ref = ""
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = line.substring(11).trim()
                        when {
                            opt.startsWith("http-user-agent=", true) -> ua = opt.substringAfter('=').trim()
                            opt.startsWith("http-referrer=", true) ||
                                opt.startsWith("http-referer=", true) -> ref = opt.substringAfter('=').trim()
                        }
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        val name = title.ifBlank { attrs["tvg-name"].orEmpty() }
                            .ifBlank { line.substringAfterLast('/') }
                        val group = attrs["group-title"].orEmpty().ifBlank { extGroup }.ifBlank { "Uncategorized" }
                        val kind = when {
                            line.contains("/movie/", true) -> Kind.MOVIE
                            line.contains("/series/", true) -> Kind.SERIES
                            else -> Kind.LIVE
                        }
                        val baseId = stableId("$name|$line")
                        var id = baseId
                        var n = 1
                        while (!ids.add(id)) id = baseId + "_" + n++
                        out += Channel(
                            id = id, name = name, group = group, url = line,
                            logo = attrs["tvg-logo"].orEmpty(), tvgId = attrs["tvg-id"].orEmpty(),
                            kind = kind, userAgent = ua, referrer = ref,
                        )
                        attrs = emptyMap(); title = ""; extGroup = ""; ua = ""; ref = ""
                    }
                }
            }
        }
        return M3uResult(out, epgUrl)
    }

    /** The title starts after the first comma that is not inside a quoted attribute. */
    private fun titleComma(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun attributes(s: String): Map<String, String> =
        attrRegex.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
}
