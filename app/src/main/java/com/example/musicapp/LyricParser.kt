package com.example.musicapp

import java.util.regex.Pattern

object LyricParser {

    fun parse(lrcContent: String): List<LyricLine> {
        val lyricLines = mutableListOf<Pair<Long, String>>()
        val pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\](.*)")

        lrcContent.lines().forEach { line ->
            val matcher = pattern.matcher(line)
            if (matcher.matches()) {
                val minutes = matcher.group(1)?.toLong() ?: 0
                val seconds = matcher.group(2)?.toLong() ?: 0
                val milliseconds = matcher.group(3)?.let {
                    if (it.length == 2) it.toLong() * 10 else it.toLong()
                } ?: 0
                val text = matcher.group(4)?.trim() ?: ""
                val time = minutes * 60 * 1000 + seconds * 1000 + milliseconds
                if (text.isNotEmpty()) {
                    lyricLines.add(Pair(time, text))
                }
            }
        }

        lyricLines.sortBy { it.first }

        val result = mutableListOf<LyricLine>()
        for (i in lyricLines.indices) {
            val startTime = lyricLines[i].first
            val text = lyricLines[i].second
            val endTime = if (i < lyricLines.size - 1) lyricLines[i + 1].first else startTime + 10000
            val duration = endTime - startTime
            result.add(LyricLine(startTime, text, duration))
        }

        return result
    }
}