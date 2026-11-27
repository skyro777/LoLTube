package com.loltube.app.player

/**
 * Site-style subtitle engine.
 *
 * The three sources' own players (vidfast/vidcore/mapple — reverse-engineered
 * from the user's saved pages) all do the SAME thing:
 *  1. download a subtitle file (SRT or VTT),
 *  2. parse it into plain cues,
 *  3. scrub the text (strip HTML tags, drop advertising/credits cues),
 *  4. render it with their OWN overlay while the <video> element itself is
 *     untouched — never a re-prepare, and the display timing is
 *     `currentTime + user-adjustable offset`.
 *
 * This mirrors that exactly for the native player: the sidecar file is parsed
 * here and rendered by a Compose overlay, so
 *  - the video stream never restarts when a subtitle is picked or re-timed,
 *  - the sync offset is a pure display-side shift (per provider — each
 *    source's video copy is trimmed differently, which is why the same
 *    subtitle file runs 2–6s off depending on the source),
 *  - the size/color/background is fully customizable (the built-in
 *    SubtitleView's fixed styling was the "too large, can't change" problem).
 */
object Subtitles {

    /** One display cue. Text may contain '\n' line breaks. */
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Parses SRT, VTT or ASS/SSA text into cues. Tolerates:
     *  - BOM, CRLF, WEBVTT header / NOTE / STYLE blocks,
     *  - both `00:00:01,000` and `00:00:01.000` (and short `01:02.5` VTT form),
     *  - missing trailing blank line / missing index numbers,
     *  - HTML-ish markup inside cue text (`<i>…</i>` etc.),
     *  - ad/credits cues (the sites drop these too — same heuristics),
     *  - ASS/SSA `Dialogue:` events — series subtitles on these CDNs are
     *    frequently full ASS scripts (`[Script Info]` with NO `-->` lines at
     *    all); that exact shape used to be rejected as "not a subtitle", which
     *    is why some series showed "subtitle not available" even though the
     *    download itself succeeded (HTTP 200).
     */
    fun parse(body: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val text = body.trimStart('﻿', '\uFEFF', ' ', '\n', '\r')
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        // ASS/SSA scripts carry no `-->` lines at all — the ASS parser must run
        // BEFORE the SRT/VTT block scanner (which would find zero cues and
        // report "not a subtitle").
        if (text.startsWith("[Script Info]", ignoreCase = true) ||
            text.lines().any { it.trim().startsWith("Dialogue:", ignoreCase = true) }
        ) {
            val ass = parseAss(text)
            if (ass.isNotEmpty()) return ass
        }

        var i = 0
        // skip a WEBVTT header block and any NOTE/STYLE/REGION preamble
        if (lines.isNotEmpty() && lines[0].startsWith("WEBVTT", ignoreCase = true)) i = 1
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("NOTE", true) || l.startsWith("STYLE", true) || l.startsWith("REGION", true)) {
                while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                i++
                continue
            }
            if (l.isEmpty()) {
                i++
                continue
            }

            // find the timestamp line in this block (index number may be missing)
            var tsIdx = i
            if (tsIdx + 1 < lines.size && lines[tsIdx + 1].contains("-->")) tsIdx += 1
            if (!lines[tsIdx].contains("-->")) {
                // not a cue block — skip a line and retry
                i = tsIdx + 1
                continue
            }

            val stamps = parseTimestamps(lines[tsIdx])
            if (stamps == null) {
                // not a valid cue block — skip past and retry
                i = tsIdx + 1
                continue
            }
            val startMs = stamps.first
            val endMs = stamps.second

            // cue text = lines until blank
            val sb = StringBuilder()
            var j = tsIdx + 1
            while (j < lines.size && lines[j].isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[j].trim())
                j++
            }
            val rawText = sb.toString().trim()
            if (rawText.isNotEmpty()) {
                val cleaned = cleanText(rawText)
                if (cleaned.isNotEmpty() && !isJunkCue(cleaned)) {
                    cues.add(Cue(startMs, maxOf(endMs, startMs), cleaned))
                }
            }
            i = j
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * The cue that should be ON SCREEN at playback position [posMs] given a
     * per-source sync [delayMs] (>0 = subtitles appear later — the fix for
     * subtitles that run ahead of the audio; <0 = appear earlier).
     * Mirrors mapple's `currentTime + timing.offset` cue lookup.
     */
    fun activeCueAt(cues: List<Cue>, posMs: Long, delayMs: Int): Cue? {
        if (cues.isEmpty()) return null
        val p = posMs - delayMs
        // cues are sorted by start; binary search for the last cue starting <= p
        var lo = 0
        var hi = cues.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].startMs <= p) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var idx = ans
        while (idx >= 0) {
            val c = cues[idx]
            if (c.endMs > p) return c
            // an earlier overlapping cue can still be live — walk back a bounded amount
            if (ans - idx > 8) break
            idx--
        }
        return null
    }

    // ---- internals -----------------------------------------------------------

    // ---- ASS / SSA (Advanced SubStation Alpha) -------------------------------

    /**
     * ASS/SSA event parser. A `Dialogue:` line is comma-separated with a hard
     * field count — `Dialogue: layer, start, end, style, name, mLR, mRR, mV,
     * effect, text` — and the TEXT field is the 10th and allowed to contain
     * commas, so the split MUST be limited (an unbounded split corrupts every
     * cue that contains a comma). Timestamps are `H:MM:SS.cc` in centiseconds —
     * NOT the millisecond form SRT uses — which is why ASS gets its own
     * timestamp parser instead of reusing [TS_LINE].
     */
    private fun parseAss(body: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = line.split(",", limit = 10)
            if (parts.size < 10) continue
            val startMs = assTimestamp(parts[1]) ?: continue
            val endMs = assTimestamp(parts[2]) ?: continue
            if (endMs <= startMs) continue
            val text = parts[9]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
            val cleaned = cleanText(text) // strips {\…} override tags
            if (cleaned.isNotEmpty() && !isJunkCue(cleaned)) {
                cues.add(Cue(startMs, endMs, cleaned))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /** `0:00:01.50` — ASS centiseconds (2 digits), also tolerates 1/3-digit ms. */
    private fun assTimestamp(s: String): Long? {
        val m = Regex("""^(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})$""").find(s.trim()) ?: return null
        val (h, min, sec, frac) = m.destructured
        val fracMs = when (frac.length) {
            1 -> (frac + "00").toLong()          // .5   -> 500 ms
            2 -> (frac + "0").toLong()            // .50  (centiseconds) -> 500 ms
            else -> frac.padEnd(3, '0').toLong() // .005 (ms) -> 5 ms
        }
        return ((h.toLong() * 3600 + min.toLong() * 60 + sec.toLong()) * 1000) + fracMs
    }

    /** `00:00:04,460 --> 00:00:07,080` (also `.` ms and mm:ss.t forms). */
    private val TS_LINE = Regex(
        """\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?"""
    )

    private fun parseTimestamps(line: String): Pair<Long, Long>? {
        val m = TS_LINE.find(line) ?: return null
        val g = m.groupValues
        fun ms(idxH: Int, idxM: Int, idxS: Int, idxF: Int): Long {
            val h = if (g[idxH].isNotEmpty()) g[idxH].trimEnd(':').toLong() else 0L
            val min = g[idxM].toLong()
            val s = g[idxS].toLong()
            var frac = 0L
            val f = g[idxF]
            if (f.isNotEmpty()) {
                val f3 = f.padEnd(3, '0')
                frac = f3.toLong()
            }
            return ((h * 3600 + min * 60 + s) * 1000) + frac
        }
        val start = ms(1, 2, 3, 4)
        val end = ms(5, 6, 7, 8)
        return if (end > start) start to end else null
    }

    /** Strip HTML tags and entities; keep line structure. */
    private fun cleanText(raw: String): String {
        var t = raw
        t = t.replace(Regex("<[^>]*>"), "")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        // SSA-style leftover braces
        t = t.replace(Regex("\\{[^}]*\\}"), "")
        return t.trim()
    }

    /**
     * Advertising / credits cues the sources scrub themselves (mapple's list:
     * "opensubtitles", "vip member", "advertise your product", "support us"
     * …; vidfast drops anything containing a domain-looking token).
     */
    private fun isJunkCue(text: String): Boolean {
        val t = text.lowercase()
        if (Regex("""\b[a-z0-9][-a-z0-9]*\.[a-z]{2,}\b""").containsMatchIn(t)) return true
        if (t.length < 4) return false
        // mapple's actual credit/ads scrub list (deliberately specific — generic
        // words like "netflix" would drop real dialogue that mentions them)
        val junkPhrases = listOf(
            "subtitles by", "synchronized by", "transcribed by", "opensubtitles",
            "subscene", "vip member", "remove all ads", "contact www",
            "support us and become", "advertise your product",
            "support us at", "rate this subtitle", "subtitles brought to you",
        )
        return junkPhrases.any { t.contains(it) }
    }
}
