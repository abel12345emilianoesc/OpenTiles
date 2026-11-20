package com.example.data

import com.example.domain.NoteKind
import com.example.domain.RhythmNote
import java.util.Locale

/**
 * Serializador compacto y determinista para listas de RhythmNote.
 * Formato por línea separada por ';': id|timeMs|durationMs|lane|isChord(1/0)|intensity|frequencyBand|kindCode
 */
object ChartSerializer {
    fun serialize(notes: List<RhythmNote>): String {
        if (notes.isEmpty()) return ""
        val sb = StringBuilder(notes.size * 32)
        for (i in notes.indices) {
            val n = notes[i]
            if (i > 0) sb.append(';')
            sb.append(n.id).append('|')
                .append(n.timeMs).append('|')
                .append(n.durationMs).append('|')
                .append(n.lane).append('|')
                .append(if (n.isChord) '1' else '0').append('|')
                .append(String.format(Locale.US, "%.3f", n.intensity)).append('|')
                .append(n.frequencyBand).append('|')
                .append(n.kind.code)
        }
        return sb.toString()
    }

    fun deserialize(raw: String): List<RhythmNote> {
        if (raw.isBlank()) return emptyList()
        val entries = raw.split(';')
        val result = ArrayList<RhythmNote>(entries.size)
        for (entry in entries) {
            val parts = entry.split('|')
            if (parts.size >= 6) {
                val id = parts[0].toIntOrNull() ?: result.size
                val timeMs = parts[1].toLongOrNull() ?: continue
                val durationMs = parts[2].toLongOrNull() ?: 0L
                val lane = (parts[3].toIntOrNull() ?: 0).coerceIn(0, 3)
                val isChord = parts[4] == "1"
                val intensity = (parts[5].toFloatOrNull() ?: 0.7f).coerceIn(0.1f, 1.0f)
                val band = if (parts.size >= 7) (parts[6].toIntOrNull() ?: lane) else lane
                val kind = if (parts.size >= 8) {
                    NoteKind.fromCode(parts[7].toIntOrNull() ?: 0)
                } else {
                    when {
                        durationMs >= 180L -> NoteKind.HOLD
                        isChord -> NoteKind.CHORD
                        intensity >= 0.86f -> NoteKind.BEAT_PULSE
                        else -> NoteKind.TAP
                    }
                }
                result.add(
                    RhythmNote(
                        id = id,
                        timeMs = timeMs,
                        durationMs = durationMs,
                        lane = lane,
                        isChord = isChord,
                        intensity = intensity,
                        frequencyBand = band,
                        kind = kind
                    )
                )
            }
        }
        return result.sortedWith(compareBy<RhythmNote> { it.timeMs }.thenBy { it.lane })
    }
}
