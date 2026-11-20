package com.example

import androidx.compose.ui.graphics.Color
import com.example.audio.AudioChartAnalyzer
import com.example.audio.BeatPhaseSegment
import com.example.audio.OnsetCandidate
import com.example.audio.SynthesizedAudioEngine
import com.example.data.AppUpdateChecker
import com.example.data.ChartSerializer
import com.example.domain.AppVisualStyle
import com.example.domain.BuiltInCatalog
import com.example.domain.DifficultyLevel
import com.example.domain.HitJudgment
import com.example.domain.HitTrailStyle
import com.example.domain.NoteKind
import com.example.domain.RhythmNote
import com.example.domain.SfxProfile
import com.example.ui.screens.computeBeatReactiveTileColors
import com.example.ui.screens.computeComboVisualTier
import com.example.ui.screens.computeRecommendedAudioOffsetMs
import com.example.ui.screens.computeTileTopY
import com.example.ui.theme.tokensForStyle
import com.example.viewmodel.GameEngine
import com.example.viewmodel.PlaybackClockSynchronizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun `DSP analyzer generates 5 difficulties with strictly increasing note fidelity and hold variety`() {
        val spec = SynthesizedAudioEngine.builtInSongs.first()
        val samples = SynthesizedAudioEngine.generateSongSamples(spec, sampleRate = 22050)
        val analyzed = AudioChartAnalyzer.analyzePcmSamples(
            samples = samples,
            sampleRate = 22050,
            durationMs = spec.durationMs
        )

        assertEquals(5, analyzed.chartsByDifficulty.size)
        assertTrue(analyzed.estimatedBpm in 85..175)

        val c1 = analyzed.chartsByDifficulty[DifficultyLevel.SUAVE]!!
        val c2 = analyzed.chartsByDifficulty[DifficultyLevel.RITMO]!!
        val c3 = analyzed.chartsByDifficulty[DifficultyLevel.FLUIDO]!!
        val c4 = analyzed.chartsByDifficulty[DifficultyLevel.INTENSO]!!
        val c5 = analyzed.chartsByDifficulty[DifficultyLevel.RESONANCIA_MAESTRA]!!

        assertTrue("Suave debe tener notas jugables", c1.size >= 8)
        assertTrue("Ritmo (${c2.size}) debe tener más notas que Suave (${c1.size})", c2.size > c1.size)
        assertTrue("Fluido (${c3.size}) debe tener más notas que Ritmo (${c2.size})", c3.size > c2.size)
        assertTrue("Intenso (${c4.size}) debe tener más notas que Fluido (${c3.size})", c4.size > c3.size)
        assertTrue("Resonancia Maestra (${c5.size}) debe tener más notas que Intenso (${c4.size})", c5.size > c4.size)

        // Verificar variedad real de tipos de teclas (HOLD de mantener pulsado + BEAT_PULSE + TAP)
        assertTrue("Debe incluir teclas de mantener pulsado (HOLD)", c3.any { it.isHold })
        assertTrue("Debe incluir teclas de pulso fuerte (BEAT_PULSE)", c3.any { it.kind == NoteKind.BEAT_PULSE })

        DifficultyLevel.entries.forEach { diff ->
            val notes = analyzed.chartsByDifficulty[diff]!!
            assertTrue(notes.all { it.lane in 0..3 })
            for (i in 1 until notes.size) {
                assertTrue(notes[i].timeMs >= notes[i - 1].timeMs)
            }
        }
    }

    @Test
    fun `WAV file synthesis and direct DSP file analysis work end to end`() {
        val tempWav = File.createTempFile("test_neon_track", ".wav")
        try {
            val spec = SynthesizedAudioEngine.builtInSongs[1]
            val samples = SynthesizedAudioEngine.generateSongSamples(spec, sampleRate = 22050)
            SynthesizedAudioEngine.writeMonoWavFile(tempWav, samples, sampleRate = 22050)
            assertTrue(tempWav.exists() && tempWav.length() > 44L)

            val fileResult = AudioChartAnalyzer.analyzeAudioFile(tempWav)
            assertEquals(5, fileResult.chartsByDifficulty.size)
            val masterNotes = fileResult.chartsByDifficulty[DifficultyLevel.RESONANCIA_MAESTRA]!!
            val easyNotes = fileResult.chartsByDifficulty[DifficultyLevel.SUAVE]!!
            assertTrue(masterNotes.size > easyNotes.size)
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun `ChartSerializer round trip preserves all note attributes and NoteKind`() {
        val original = listOf(
            RhythmNote(id = 1, timeMs = 500L, durationMs = 0L, lane = 0, isChord = false, intensity = 0.85f, frequencyBand = 0, kind = NoteKind.TAP),
            RhythmNote(id = 2, timeMs = 950L, durationMs = 450L, lane = 2, isChord = false, intensity = 0.92f, frequencyBand = 2, kind = NoteKind.HOLD),
            RhythmNote(id = 3, timeMs = 1500L, durationMs = 0L, lane = 1, isChord = true, intensity = 0.78f, frequencyBand = 1, kind = NoteKind.CHORD),
            RhythmNote(id = 4, timeMs = 1900L, durationMs = 0L, lane = 3, isChord = false, intensity = 0.95f, frequencyBand = 3, kind = NoteKind.BEAT_PULSE)
        )
        val encoded = ChartSerializer.serialize(original)
        val decoded = ChartSerializer.deserialize(encoded)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i].id, decoded[i].id)
            assertEquals(original[i].timeMs, decoded[i].timeMs)
            assertEquals(original[i].durationMs, decoded[i].durationMs)
            assertEquals(original[i].lane, decoded[i].lane)
            assertEquals(original[i].isChord, decoded[i].isChord)
            assertEquals(original[i].kind, decoded[i].kind)
        }
    }

    @Test
    fun `beat reactive tile colors vary with frequency band beat pulse and note kind`() {
        val theme = BuiltInCatalog.tileThemes.first()
        val bassNote = RhythmNote(id = 1, timeMs = 1000L, lane = 0, frequencyBand = 0, kind = NoteKind.TAP)
        val beatNote = RhythmNote(id = 2, timeMs = 1500L, lane = 3, frequencyBand = 3, kind = NoteKind.BEAT_PULSE)

        val cBassCalm = computeBeatReactiveTileColors(
            note = bassNote,
            theme = theme,
            songPrimary = Color(0xFFF43F5E),
            songSecondary = Color(0xFF10B981),
            beatPulse = 0.0f,
            isFever = false,
            beatReactiveEnabled = true
        )
        val cBeatPeak = computeBeatReactiveTileColors(
            note = beatNote,
            theme = theme,
            songPrimary = Color(0xFFF43F5E),
            songSecondary = Color(0xFF10B981),
            beatPulse = 1.0f,
            isFever = false,
            beatReactiveEnabled = true
        )
        val cStatic = computeBeatReactiveTileColors(
            note = bassNote,
            theme = theme,
            songPrimary = Color(0xFFF43F5E),
            songSecondary = Color(0xFF10B981),
            beatPulse = 1.0f,
            isFever = false,
            beatReactiveEnabled = false
        )

        assertNotEquals(cBassCalm.top, cBeatPeak.top)
        assertEquals(theme.tileTopColor, cStatic.top)
    }

    @Test
    fun `global visual styles include true black OLED and light frost tokens`() {
        val oled = tokensForStyle(AppVisualStyle.OLED_PURE)
        assertEquals(Color(0xFF000000), oled.canvasBackground)
        assertEquals(Color(0xFF000000), oled.navSurface)
        assertTrue(oled.isDark)

        val light = tokensForStyle(AppVisualStyle.FROST_LIGHT)
        assertFalse(light.isDark)
    }

    @Test
    fun `GameEngine evaluates timing judgments combo fever hold completion and early release`() {
        val notes = listOf(
            RhythmNote(id = 1, timeMs = 1000L, durationMs = 0L, lane = 0, kind = NoteKind.TAP),
            RhythmNote(id = 2, timeMs = 1500L, durationMs = 0L, lane = 1, kind = NoteKind.BEAT_PULSE),
            RhythmNote(id = 3, timeMs = 2000L, durationMs = 400L, lane = 2, kind = NoteKind.HOLD),
            RhythmNote(id = 4, timeMs = 3000L, durationMs = 0L, lane = 3, kind = NoteKind.TAP)
        )
        val engine = GameEngine(
            songId = "song_1",
            songTitle = "Test Track",
            artist = "Test Artist",
            durationMs = 4000L,
            bpm = 120,
            difficulty = DifficultyLevel.FLUIDO,
            notes = notes
        )

        engine.updateClock(1015L)
        val j1 = engine.onLaneDown(lane = 0, atTimeMs = 1015L)
        engine.onLaneUp(0)
        assertEquals(HitJudgment.PERFECT, j1)
        assertEquals(1, engine.combo)

        engine.updateClock(1575L)
        val j2 = engine.onLaneDown(lane = 1, atTimeMs = 1575L)
        engine.onLaneUp(1)
        assertEquals(HitJudgment.GREAT, j2)
        assertEquals(2, engine.combo)

        engine.updateClock(2000L)
        val j3 = engine.onLaneDown(lane = 2, atTimeMs = 2000L)
        assertEquals(HitJudgment.PERFECT, j3)
        assertTrue(engine.noteStates[2].isHolding)

        val scoreBeforeHoldComplete = engine.score
        engine.updateClock(2420L)
        engine.onLaneUp(2)
        assertTrue(engine.noteStates[2].holdCompleted)
        assertEquals(1, engine.holdsCompletedCount)
        assertTrue(engine.score > scoreBeforeHoldComplete)

        val missed = engine.updateClock(3350L)
        assertTrue(missed)
        assertEquals(0, engine.combo)
        assertEquals(1, engine.missCount)
        assertEquals(3, engine.maxCombo)

        val summary = engine.buildSessionResult(isNewRecord = true)
        assertEquals(1, summary.holdsCompleted)
        assertTrue(summary.earnedFragments > 0)
        assertNotNull(summary.rank)
        assertFalse(summary.rank.isBlank())
    }

    @Test
    fun `all 4 built-in songs produce 5 coherent difficulties with holds and zero same-lane overlaps`() {
        for (spec in SynthesizedAudioEngine.builtInSongs) {
            val samples = SynthesizedAudioEngine.generateSongSamples(spec, sampleRate = 22050)
            val analyzed = AudioChartAnalyzer.analyzePcmSamples(
                samples = samples,
                sampleRate = 22050,
                durationMs = spec.durationMs
            )
            assertEquals("Debe generar 5 dificultades para ${spec.title}", 5, analyzed.chartsByDifficulty.size)

            var prevCount = 0
            DifficultyLevel.entries.forEach { diff ->
                val notes = analyzed.chartsByDifficulty[diff]!!
                assertTrue("${spec.title} [${diff.displayName}] debe tener más notas que la anterior", notes.size > prevCount)
                prevCount = notes.size

                if (diff.level >= 2) {
                    assertTrue("${spec.title} [${diff.displayName}] debe incluir teclas HOLD", notes.any { it.isHold })
                }

                // Verificar que en un mismo carril ninguna nota comience mientras otra HOLD sigue activa
                val notesByLane = notes.groupBy { it.lane }
                notesByLane.forEach { (lane, laneNotes) ->
                    val sorted = laneNotes.sortedBy { it.timeMs }
                    for (i in 1 until sorted.size) {
                        val prev = sorted[i - 1]
                        val curr = sorted[i]
                        assertTrue(
                            "Solapamiento en carril $lane de ${spec.title}: prevEnd=${prev.endTimeMs}, curr=${curr.timeMs}",
                            curr.timeMs >= prev.endTimeMs
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `GameEngine awards chord bonus and handles early hold release gracefully`() {
        val notes = listOf(
            RhythmNote(id = 1, timeMs = 1000L, durationMs = 0L, lane = 0, isChord = true, kind = NoteKind.CHORD),
            RhythmNote(id = 2, timeMs = 1000L, durationMs = 0L, lane = 2, isChord = true, kind = NoteKind.CHORD),
            RhythmNote(id = 3, timeMs = 2000L, durationMs = 600L, lane = 1, kind = NoteKind.HOLD)
        )
        val engine = GameEngine(
            songId = "song_chord",
            songTitle = "Chord Test",
            artist = "Tester",
            durationMs = 4000L,
            bpm = 120,
            difficulty = DifficultyLevel.FLUIDO,
            notes = notes
        )

        engine.updateClock(1005L)
        assertEquals(HitJudgment.PERFECT, engine.onLaneDown(0, 1005L))
        assertEquals(HitJudgment.PERFECT, engine.onLaneDown(2, 1005L))
        engine.onLaneUp(0)
        engine.onLaneUp(2)
        assertEquals(2, engine.combo)

        // Soltar tecla HOLD antes del 68% (~20% de progreso) no suma holdsCompletedCount completo pero cierra limpio
        engine.updateClock(2000L)
        assertEquals(HitJudgment.PERFECT, engine.onLaneDown(1, 2000L))
        engine.updateClock(2120L)
        engine.onLaneUp(1)
        engine.updateClock(2130L)
        assertFalse(engine.noteStates[2].isHolding)
        assertTrue(engine.noteStates[2].holdCompleted)
        assertEquals(0, engine.holdsCompletedCount)
    }

    @Test
    fun `RhythmRepository resolves m4a mp3 wav ogg by extension and MIME type fallback`() {
        assertEquals("m4a", com.example.data.RhythmRepository.resolveSupportedExtension("mi_cancion.m4a", null))
        assertEquals("m4a", com.example.data.RhythmRepository.resolveSupportedExtension("PISTA.M4A", "application/octet-stream"))
        assertEquals("m4a", com.example.data.RhythmRepository.resolveSupportedExtension("TrackSinExtension", "audio/mp4"))
        assertEquals("m4a", com.example.data.RhythmRepository.resolveSupportedExtension(null, "audio/x-m4a"))
        assertEquals("m4a", com.example.data.RhythmRepository.resolveSupportedExtension(null, "audio/m4a"))
        assertEquals("mp3", com.example.data.RhythmRepository.resolveSupportedExtension("tema.mp3", "audio/mpeg"))
        assertEquals("wav", com.example.data.RhythmRepository.resolveSupportedExtension("base.wav", null))
        assertEquals("ogg", com.example.data.RhythmRepository.resolveSupportedExtension("loop.ogg", null))
        assertEquals(null, com.example.data.RhythmRepository.resolveSupportedExtension("documento.pdf", "application/pdf"))
    }

    @Test
    fun `PlaybackClockSynchronizer prevents start and unpause stutter and enforces monotonicity`() {
        val sync = PlaybackClockSynchronizer(initialClockMs = 0L)
        val frameMs = 16_666_667L // ~60fps

        // Primer frame ancla exactamente en 0ms sin salto
        var now = 1_000_000_000L
        assertEquals(0L, sync.stepFrame(now, mediaPlayerPosMs = 0L, isMediaPlaying = true, audioOffsetMs = 0L))

        // Durante los primeros 260ms de warmup, aunque MediaPlayer reporte 0ms o jitter, el reloj avanza suave sin tirones
        var prevClock = 0L
        for (i in 1..15) {
            now += frameMs
            val c = sync.stepFrame(now, mediaPlayerPosMs = 0L, isMediaPlaying = true, audioOffsetMs = 0L)
            assertTrue("El reloj debe avanzar monotónicamente (frame $i: $c >= $prevClock)", c >= prevClock)
            assertTrue("El incremento por frame no debe dar tirones (${c - prevClock}ms)", (c - prevClock) in 14L..19L)
            prevClock = c
        }

        // Simular pausa y reanudación en 12_000ms con MediaPlayer atrasado inicialmente en 11_800ms
        sync.resetForStartOrResume(12_000L)
        now += 500_000_000L
        val resumeAnchor = sync.stepFrame(now, mediaPlayerPosMs = 11_800L, isMediaPlaying = true, audioOffsetMs = 0L)
        assertEquals(12_000L, resumeAnchor)

        // Incluso tras pasar el warmup con MediaPlayer atrasado, el reloj NUNCA retrocede
        prevClock = resumeAnchor
        for (i in 1..30) {
            now += frameMs
            val c = sync.stepFrame(now, mediaPlayerPosMs = 11_900L + (i * 16L), isMediaPlaying = true, audioOffsetMs = 0L)
            assertTrue("El reloj jamás debe retroceder al despausar ($c >= $prevClock)", c >= prevClock)
            prevClock = c
        }

        // Un pico grande de CPU (250ms entre frames) debe limitarse a <= 48ms para evitar teletransporte de teclas
        now += 250_000_000L
        val afterLagSpike = sync.stepFrame(now, mediaPlayerPosMs = null, isMediaPlaying = false, audioOffsetMs = 0L)
        assertTrue("El salto por lag spike debe quedar limitado a <= 48ms", (afterLagSpike - prevClock) <= 48L)
    }

    @Test
    fun `computeComboVisualTier grows in size and changes color across combo tiers`() {
        val t0 = computeComboVisualTier(0)
        val t5 = computeComboVisualTier(5)
        val t15 = computeComboVisualTier(15)
        val t30 = computeComboVisualTier(30)
        val t48 = computeComboVisualTier(48)

        assertEquals(0f, t0.alpha, 0.001f)
        assertTrue(t5.baseScale > t0.baseScale)
        assertTrue(t15.baseScale > t5.baseScale)
        assertTrue(t30.baseScale > t15.baseScale)
        assertTrue(t48.baseScale > t30.baseScale)

        assertNotEquals(t5.accentColor, t15.accentColor)
        assertNotEquals(t15.accentColor, t30.accentColor)
        assertNotEquals(t30.accentColor, t48.accentColor)

        assertEquals("COMBO", t5.badgeLabel)
        assertEquals("RITMO x2", t15.badgeLabel)
        assertEquals("FIEBRE x4", t30.badgeLabel)
        assertEquals("RESONANCIA x8", t48.badgeLabel)
    }

    @Test
    fun `expanded Shop catalog has 25 items and all 8 SFX profiles synthesize audio`() {
        assertEquals(11, BuiltInCatalog.tileThemes.size)
        assertEquals(8, SfxProfile.entries.size)
        assertEquals(6, HitTrailStyle.entries.size)

        for (profile in SfxProfile.entries) {
            val tempWav = File.createTempFile("sfx_${profile.id}_", ".wav")
            try {
                SynthesizedAudioEngine.writeLaneHitSfxWav(tempWav, profile, lane = 2)
                assertTrue("El perfil SFX ${profile.displayName} debe generar un archivo WAV válido", tempWav.exists() && tempWav.length() > 1000L)
            } finally {
                tempWav.delete()
            }
        }
    }

    @Test
    fun `frameIndexToTimeMs eliminates 44_1kHz integer truncation drift over long songs`() {
        // 44.1 kHz, hop 512 -> tras 17,226 frames (~200 s), el cálculo en coma flotante debe ser exacto a ±1 ms
        val sr = 44100
        val hop = 512
        val frameIndex = 17_226
        val expectedExactMs = Math.round((frameIndex.toLong() * hop.toLong()).toDouble() * 1000.0 / sr.toDouble())
        val actualMs = AudioChartAnalyzer.frameIndexToTimeMs(frameIndex, hop, sr)
        assertEquals(expectedExactMs, actualMs)
        assertEquals(expectedExactMs + 25L, AudioChartAnalyzer.frameIndexToTimeMs(frameIndex, hop, sr, startOffsetMs = 25L))
    }

    @Test
    fun `downsampleBoxcar averages discarded samples to suppress Nyquist aliasing`() {
        // Señal alternante +1, -1 (frecuencia de Nyquist): tomar muestras pares daría todo +1.0 (aliasing),
        // pero el promediado boxcar de paso 2 debe cancelarla a 0.0.
        val nyquist = FloatArray(16) { if (it % 2 == 0) 1f else -1f }
        val filtered = AudioChartAnalyzer.downsampleBoxcar(nyquist, step = 2)
        assertEquals(8, filtered.size)
        for (v in filtered) {
            assertEquals(0f, v, 0.0001f)
        }
    }

    @Test
    fun `computeDecoderStartOffsetMs compensates presentationTimeUs and encoder delay`() {
        assertEquals(42L, AudioChartAnalyzer.computeDecoderStartOffsetMs(42_000L, encoderDelaySamples = 0, inputSampleRate = 44100))
        assertEquals(-25L, AudioChartAnalyzer.computeDecoderStartOffsetMs(0L, encoderDelaySamples = 1105, inputSampleRate = 44100))
        assertEquals(0L, AudioChartAnalyzer.computeDecoderStartOffsetMs(null, encoderDelaySamples = 0, inputSampleRate = 44100))
    }

    @Test
    fun `estimateExactBpm avoids 160 vs 80 BPM double-time error and estimates beat phase accurately`() {
        // Sintetizar pista de clics percusivos a 80 BPM (periodo = 750 ms) con desplazamiento inicial de +180 ms
        val sr = 22050
        val durationSec = 16
        val samples = FloatArray(sr * durationSec)
        val beatPeriodSec = 60.0 / 80.0 // 0.75 s
        val offsetSec = 0.180
        var tBeat = offsetSec
        while (tBeat < durationSec - 0.5) {
            val startIdx = (tBeat * sr).toInt()
            for (k in 0 until (sr * 0.09).toInt()) {
                val idx = startIdx + k
                if (idx >= samples.size) break
                val lt = k.toDouble() / sr.toDouble()
                val env = exp(-lt * 38.0)
                samples[idx] = (sin(2.0 * PI * 110.0 * lt) * env * 0.9).toFloat()
            }
            tBeat += beatPeriodSec
        }

        val analyzed = AudioChartAnalyzer.analyzePcmSamples(
            samples = samples,
            sampleRate = sr,
            durationMs = durationSec * 1000L
        )

        assertTrue(
            "Debe detectar ~80 BPM sin duplicar a 160 BPM (detectado: ${analyzed.estimatedBpm})",
            analyzed.estimatedBpm in 76..84
        )
        // Fase del primer beat módulo 750 ms debe estar cerca de 180 ms (±35 ms)
        val beatMs = 750L
        val phaseErr = minOf(
            abs((analyzed.beatOffsetMs % beatMs) - 180L),
            beatMs - abs((analyzed.beatOffsetMs % beatMs) - 180L)
        )
        assertTrue(
            "La fase del primer beat (${analyzed.beatOffsetMs} ms) debe coincidir con ~180 ms (error=$phaseErr ms)",
            phaseErr <= 35L
        )
    }

    @Test
    fun `selectCandidatesByImportance prefers stronger peak over earlier weak peak within minIntervalMs`() {
        val candidates = listOf(
            OnsetCandidate(timeMs = 1000L, strength = 0.30f, dominantBand = 1, secondaryBand = null, sustainDurationMs = 0L),
            OnsetCandidate(timeMs = 1060L, strength = 0.95f, dominantBand = 0, secondaryBand = null, sustainDurationMs = 0L),
            OnsetCandidate(timeMs = 1500L, strength = 0.80f, dominantBand = 2, secondaryBand = null, sustainDurationMs = 0L)
        )
        val chosen = AudioChartAnalyzer.selectCandidatesByImportance(
            candidates = candidates,
            minIntervalMs = 200L,
            beatOffsetMs = 60L,
            beatPeriodMs = 500.0
        )
        assertEquals(2, chosen.size)
        assertEquals(1060L, chosen[0].timeMs)
        assertEquals(1500L, chosen[1].timeMs)
    }

    @Test
    fun `softQuantizeToBeatGrid snaps within 25ms and preserves off-grid notes outside tolerance`() {
        // 120 BPM -> beatPeriodMs = 500ms, subdivisión 1/8 = 250ms, beatOffsetMs = 100ms -> rejilla en 100, 350, 600, 850, 1100...
        val snapped = AudioChartAnalyzer.softQuantizeToBeatGrid(
            timeMs = 1118L,
            beatOffsetMs = 100L,
            beatPeriodMs = 500.0,
            subdivisionsPerBeat = 2,
            maxSnapMs = 25.0,
            pullStrength = 0.80
        )
        // Error = -18 ms -> pull 80% = -14.4 ms -> ~1104 ms (más cerca de 1100 ms)
        assertTrue("Debe acercar suavemente 1118ms hacia 1100ms (obtenido: $snapped)", snapped in 1102L..1106L)

        val untouched = AudioChartAnalyzer.softQuantizeToBeatGrid(
            timeMs = 1165L,
            beatOffsetMs = 100L,
            beatPeriodMs = 500.0,
            subdivisionsPerBeat = 2,
            maxSnapMs = 25.0,
            pullStrength = 0.80
        )
        assertEquals(1165L, untouched)
    }

    @Test
    fun `PlaybackClockSynchronizer handles negative pre-roll smooth PLL pull and 120ms hard snap`() {
        val sync = PlaybackClockSynchronizer(initialClockMs = -950L)
        assertTrue(sync.isInPreRoll)

        var now = 1_000_000_000L
        val frameNs = 20_000_000L // 20 ms por frame
        assertEquals(-950L, sync.stepFrame(now, mediaPlayerPosMs = null, isMediaPlaying = false, audioOffsetMs = 0L))

        // Avanzar 48 frames (960 ms) para cruzar t = 0 ms
        var c = -950L
        repeat(48) {
            now += frameNs
            c = sync.stepFrame(now, mediaPlayerPosMs = null, isMediaPlaying = false, audioOffsetMs = 0L)
        }
        assertFalse(sync.isInPreRoll)
        assertTrue("Debe haber superado 0 ms al concluir el pre-roll ($c)", c >= 0L)

        // Arrancar audio y superar warmup (220 ms -> 12 frames de 20 ms = 240 ms)
        sync.onAudioPlaybackStarted()
        repeat(12) {
            now += frameNs
            c = sync.stepFrame(now, mediaPlayerPosMs = c, isMediaPlaying = true, audioOffsetMs = 0L)
        }

        // Introducir error moderado (+50 ms > 8 ms y <= 120 ms): PLL corrige ~12% (~6 ms extra), no salta de golpe
        val beforePll = c
        now += frameNs
        val afterPll = sync.stepFrame(now, mediaPlayerPosMs = beforePll + 20L + 50L, isMediaPlaying = true, audioOffsetMs = 0L)
        val stepPll = afterPll - beforePll
        assertTrue("PLL debe corregir suavemente ~26ms (20ms + 12% de 50ms), no 70ms (obtenido: $stepPll)", stepPll in 23L..30L)

        // Introducir error severo (+180 ms > 120 ms): debe aplicar salto directo hacia ADELANTE
        val targetSevere = afterPll + 20L + 180L
        now += frameNs
        val afterSnap = sync.stepFrame(now, mediaPlayerPosMs = targetSevere, isMediaPlaying = true, audioOffsetMs = 0L)
        assertEquals(targetSevere, afterSnap)
    }

    @Test
    fun `PlaybackClockSynchronizer never jumps backwards or freezes when MediaPlayer lags after song start`() {
        // Simula arranque real en 60 Hz (16.67 ms) y 120 Hz (8.33 ms) con latencia inicial de AudioTrack de -170 ms
        for (frameDurationNs in listOf(16_666_667L, 8_333_333L)) {
            val sync = PlaybackClockSynchronizer(initialClockMs = -950L)
            var now = 2_000_000_000L
            var prevClock = sync.stepFrame(now, mediaPlayerPosMs = null, isMediaPlaying = false, audioOffsetMs = 0L)

            // Cruzar pre-roll (-950 ms -> 0 ms)
            while (prevClock < 0L) {
                now += frameDurationNs
                val c = sync.stepFrame(now, mediaPlayerPosMs = null, isMediaPlaying = false, audioOffsetMs = 0L)
                assertTrue("Durante pre-roll el reloj nunca debe retroceder ($c < $prevClock)", c >= prevClock)
                prevClock = c
            }

            // Disparar MediaPlayer en t = 0 ms, pero AudioTrack tarda 170 ms en empezar a emitir audio real
            sync.onAudioPlaybackStarted()
            var wallSinceStartMs = 0.0
            val audioStartupLagMs = 170.0
            val frameMs = frameDurationNs / 1_000_000.0

            // Simular 900 ms tras el inicio del audio
            val totalFrames = (900.0 / frameMs).toInt()
            var finalAudioPos = 0L
            repeat(totalFrames) {
                now += frameDurationNs
                wallSinceStartMs += frameMs
                val simulatedMediaPos = (wallSinceStartMs - audioStartupLagMs).coerceAtLeast(0.0).toLong()
                finalAudioPos = simulatedMediaPos

                val c = sync.stepFrame(
                    frameNanos = now,
                    mediaPlayerPosMs = simulatedMediaPos,
                    isMediaPlaying = true,
                    audioOffsetMs = 0L
                )
                assertTrue(
                    "El reloj NUNCA debe retroceder cuando el audio arranca atrasado (prev=$prevClock, curr=$c, media=$simulatedMediaPos)",
                    c >= prevClock
                )
                assertTrue(
                    "Las teclas NUNCA deben congelarse en 0 px/s durante frenado suave (prev=$prevClock, curr=$c)",
                    c >= prevClock + 4L
                )
                prevClock = c
            }

            // Al cabo de ~900 ms, el reloj visual debe haber convergido exactamente con el audio real (<= 10 ms)
            val residualError = abs(prevClock - finalAudioPos)
            assertTrue(
                "El reloj visual ($prevClock ms) debe converger suavemente al audio real ($finalAudioPos ms), error=$residualError ms",
                residualError <= 10L
            )
        }

        // También verificar con offset negativo de Bluetooth (-140 ms) sin salto hacia atrás
        val btSync = PlaybackClockSynchronizer(initialClockMs = 0L)
        btSync.onAudioPlaybackStarted()
        var btNow = 5_000_000_000L
        var btPrev = btSync.stepFrame(btNow, mediaPlayerPosMs = 0L, isMediaPlaying = true, audioOffsetMs = -140L)
        var wallMs = 0L
        repeat(60) {
            btNow += 16_000_000L
            wallMs += 16L
            val curr = btSync.stepFrame(btNow, mediaPlayerPosMs = wallMs, isMediaPlaying = true, audioOffsetMs = -140L)
            assertTrue("Con offset Bluetooth negativo el reloj nunca debe retroceder ($curr < $btPrev)", curr >= btPrev)
            btPrev = curr
        }
        val expectedBtClock = (wallMs - 140L).coerceAtLeast(0L)
        assertTrue(
            "Con offset Bluetooth (-140 ms), el reloj ($btPrev ms) debe converger a $expectedBtClock ms",
            abs(btPrev - expectedBtClock) <= 10L
        )
    }

    @Test
    fun `tile center aligns exactly with hitLineY and metronome calibration computes trimmed Bluetooth offset`() {
        val hitLineY = 1200f
        val tileHeight = 152f
        val topY = computeTileTopY(hitLineY, tileHeight)
        assertEquals(hitLineY, topY + tileHeight * 0.5f, 0.001f)

        // Simular audífonos Bluetooth con +110 ms de retraso + 2 outliers (-20 ms y +230 ms)
        val errors = listOf(108L, 112L, -20L, 110L, 114L, 230L, 106L, 110L)
        val recommended = computeRecommendedAudioOffsetMs(errors)
        assertEquals(-110L, recommended)

        // Verificar generación WAV de clic acentuado y normal de metrónomo
        val accentWav = File.createTempFile("metro_acc_", ".wav")
        val beatWav = File.createTempFile("metro_beat_", ".wav")
        try {
            SynthesizedAudioEngine.writeMetronomeClickWav(accentWav, isAccent = true)
            SynthesizedAudioEngine.writeMetronomeClickWav(beatWav, isAccent = false)
            assertTrue(accentWav.exists() && accentWav.length() > 500L)
            assertTrue(beatWav.exists() && beatWav.length() > 500L)
        } finally {
            accentWav.delete()
            beatWav.delete()
        }
    }

    @Test
    fun `sanitizeChartForPhonePlayability eliminates impossible 3-finger holds same-hand conflicts and tight lane overlaps`() {
        // Caso patológico sintético: hold en mano izquierda (carril 0, 1000..2000ms) con:
        // - acorde simultáneo en carriles 2 y 3 (¡requeriría 3 dedos!)
        // - toque en la misma mano izquierda (carril 1 a 1300ms)
        // - segundo hold solapado en carril 2 (1400..2100ms)
        // - toque válido en mano contraria (carril 3 a 1500ms)
        // - dos notas demasiado juntas en el mismo carril (3000ms y 3080ms)
        // - acorde en la misma mano (carriles 0 y 1 a 4000ms)
        val pathological = listOf(
            RhythmNote(id = 1, timeMs = 1000L, durationMs = 1000L, lane = 0, kind = NoteKind.HOLD),
            RhythmNote(id = 2, timeMs = 1200L, durationMs = 0L, lane = 2, isChord = true, kind = NoteKind.CHORD),
            RhythmNote(id = 3, timeMs = 1200L, durationMs = 0L, lane = 3, isChord = true, kind = NoteKind.CHORD),
            RhythmNote(id = 4, timeMs = 1300L, durationMs = 0L, lane = 1, kind = NoteKind.TAP),
            RhythmNote(id = 5, timeMs = 1400L, durationMs = 700L, lane = 2, kind = NoteKind.HOLD),
            RhythmNote(id = 6, timeMs = 1500L, durationMs = 0L, lane = 3, kind = NoteKind.TAP),
            RhythmNote(id = 7, timeMs = 3000L, durationMs = 0L, lane = 2, kind = NoteKind.TAP),
            RhythmNote(id = 8, timeMs = 3080L, durationMs = 0L, lane = 2, kind = NoteKind.TAP),
            RhythmNote(id = 9, timeMs = 4000L, durationMs = 0L, lane = 0, isChord = true, kind = NoteKind.CHORD),
            RhythmNote(id = 10, timeMs = 4000L, durationMs = 0L, lane = 1, isChord = true, kind = NoteKind.CHORD)
        )

        // En dificultad nivel 3 (Fluido): mantiene hold 0, rechaza 3er dedo, reasigna o mantiene solo 1 dedo en mano contraria
        val sanitizedL3 = AudioChartAnalyzer.sanitizeChartForPhonePlayability(pathological, DifficultyLevel.FLUIDO)
        assertPhonePlayabilityInvariants(sanitizedL3, DifficultyLevel.FLUIDO, "synthetic L3")

        // En dificultad nivel 1 (Suave): ningún toque simultáneo durante un HOLD
        val sanitizedL1 = AudioChartAnalyzer.sanitizeChartForPhonePlayability(pathological, DifficultyLevel.SUAVE)
        assertPhonePlayabilityInvariants(sanitizedL1, DifficultyLevel.SUAVE, "synthetic L1")

        // Comprobar invariantes ergonómicas en TODAS las canciones base y las 5 dificultades
        for (spec in SynthesizedAudioEngine.builtInSongs) {
            val samples = SynthesizedAudioEngine.generateSongSamples(spec, sampleRate = 22050)
            val analyzed = AudioChartAnalyzer.analyzePcmSamples(samples, 22050, spec.durationMs)
            for (diff in DifficultyLevel.entries) {
                val chart = analyzed.chartsByDifficulty[diff]!!
                assertPhonePlayabilityInvariants(chart, diff, "${spec.title} [${diff.displayName}]")
            }
        }
    }

    private fun assertPhonePlayabilityInvariants(
        notes: List<RhythmNote>,
        difficulty: DifficultyLevel,
        label: String
    ) {
        // 1. Nunca dos notas en el mismo carril más cerca de 220 ms (ni solapadas con el fin de un HOLD)
        notes.groupBy { it.lane }.forEach { (lane, laneNotes) ->
            val sorted = laneNotes.sortedBy { it.timeMs }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                assertTrue(
                    "$label carril $lane demasiado junto: prevEnd=${prev.endTimeMs}, curr=${curr.timeMs}",
                    curr.timeMs >= prev.endTimeMs + 220L || (!prev.isHold && curr.timeMs - prev.timeMs >= 220L)
                )
            }
        }

        // 2. Máximo 2 dedos simultáneos en cualquier instante (incluyendo holds activos) y nunca 2 holds solapados
        val holds = notes.filter { it.isHold }
        for (i in holds.indices) {
            for (j in i + 1 until holds.size) {
                val h1 = holds[i]
                val h2 = holds[j]
                val overlap = h1.timeMs <= h2.endTimeMs && h2.timeMs <= h1.endTimeMs
                assertFalse("$label tiene dos HOLD solapados ($h1 y $h2)", overlap)
            }
        }

        // 3. Durante cualquier HOLD:
        //    - En niveles 1-2: 0 notas concurrentes
        //    - En niveles 3-5: solo notas individuales en la mano contraria (0..1 vs 2..3), jamás acordes (3 dedos)
        for (hold in holds) {
            val holdIsLeftHand = hold.lane <= 1
            val duringHold = notes.filter { other ->
                other.id != hold.id && other.timeMs >= hold.timeMs && other.timeMs <= hold.endTimeMs
            }
            if (difficulty.level <= 2) {
                assertTrue("$label no debe pedir toques durante un HOLD en nivel ${difficulty.level}", duringHold.isEmpty())
            } else {
                for (other in duringHold) {
                    assertFalse("$label no debe generar acordes durante un HOLD", other.isChord)
                    val otherIsLeftHand = other.lane <= 1
                    assertNotEquals(
                        "$label nota ${other.lane} en misma mano que HOLD ${hold.lane} en t=${other.timeMs}",
                        holdIsLeftHand,
                        otherIsLeftHand
                    )
                }
            }
        }

        // 4. Ningún timestamp puede tener >2 notas simultáneas, y cualquier acorde de 2 notas debe ser mano dividida
        notes.groupBy { it.timeMs }.forEach { (timeMs, group) ->
            assertTrue("$label tiene ${group.size} notas simultáneas en t=$timeMs (>2 imposible con pulgares)", group.size <= 2)
            if (group.size == 2) {
                val left = group.count { it.lane <= 1 }
                val right = group.count { it.lane >= 2 }
                assertEquals("$label acorde en t=$timeMs debe tener 1 nota por mano", 1, left)
                assertEquals("$label acorde en t=$timeMs debe tener 1 nota por mano", 1, right)
            }
        }
    }

    @Test
    fun `HPSS separates horizontal harmonic tones from vertical percussive hits and preserves HOLD through snare`() {
        // 1) Verificación directa de computeHpss en espectrograma sintético (40 frames x 32 bins)
        val spec = Array(40) { FloatArray(32) }
        // Línea horizontal sostenida (armónica) en el bin 10 a lo largo de todos los frames
        for (t in 0 until 40) spec[t][10] = 3.0f
        // Golpe vertical de banda ancha (percusivo) en t = 20 en todos los bins
        for (b in 0 until 32) spec[20][b] = maxOf(spec[20][b], 3.0f)

        val hpss = AudioChartAnalyzer.computeHpss(spec, timeRadius = 4, freqRadius = 4)
        // Fuera del golpe percusivo (t = 10, bin = 10), la energía debe ir a harmonicSpec
        assertTrue(
            "La línea horizontal debe clasificarse como armónica (H=${hpss.harmonicSpec[10][10]}, P=${hpss.percussiveSpec[10][10]})",
            hpss.harmonicSpec[10][10] > 2.5f && hpss.percussiveSpec[10][10] < 0.2f
        )
        // En el golpe vertical fuera del bin armónico (t = 20, bin = 22), la energía debe ir a percussiveSpec
        assertTrue(
            "El golpe vertical de banda ancha debe clasificarse como percusivo (P=${hpss.percussiveSpec[20][22]}, H=${hpss.harmonicSpec[20][22]})",
            hpss.percussiveSpec[20][22] > 2.5f && hpss.harmonicSpec[20][22] < 0.2f
        )

        // 2) Prueba end-to-end PCM: notas armónicas sostenidas de 1.1 s con un golpe seco de tarola justo en medio
        val sr = 22050
        val durationSec = 12
        val samples = FloatArray(sr * durationSec)
        val rng = Random(42L)
        val sustainedStartsSec = listOf(1.0, 3.0, 5.0, 7.0, 9.0)
        for (startSec in sustainedStartsSec) {
            val s0 = (startSec * sr).toInt()
            val len = (1.15 * sr).toInt()
            for (k in 0 until len) {
                val idx = s0 + k
                if (idx >= samples.size) break
                val lt = k.toDouble() / sr.toDouble()
                val attackEnv = (lt / 0.015).coerceAtMost(1.0)
                val releaseEnv = ((1.15 - lt) / 0.04).coerceIn(0.0, 1.0)
                val tone = sin(2.0 * PI * 440.0 * lt) * 0.48 + sin(2.0 * PI * 880.0 * lt) * 0.16
                samples[idx] += (tone * attackEnv * releaseEnv).toFloat()
            }
            // Superponer golpe seco de tarola (ruido blanco de 35 ms) exactamente 400 ms después del inicio de la nota
            val snareStart = ((startSec + 0.40) * sr).toInt()
            val snareLen = (0.035 * sr).toInt()
            for (k in 0 until snareLen) {
                val idx = snareStart + k
                if (idx >= samples.size) break
                val lt = k.toDouble() / sr.toDouble()
                val snareEnv = exp(-lt * 85.0)
                samples[idx] += ((rng.nextDouble() * 2.0 - 1.0) * 0.55 * snareEnv).toFloat()
            }
        }

        val analyzed = AudioChartAnalyzer.analyzePcmSamples(samples, sr, durationSec * 1000L)
        val fluidoChart = analyzed.chartsByDifficulty[DifficultyLevel.FLUIDO]!!
        val longHolds = fluidoChart.filter { it.isHold && it.durationMs >= 550L }
        assertTrue(
            "HPSS debe mantener las notas HOLD (>= 550 ms) sin que el golpe de tarola en +400 ms las corte (holds=${fluidoChart.filter { it.isHold }.map { it.durationMs }})",
            longHolds.isNotEmpty()
        )
    }

    @Test
    fun `segmented beat tracking tracks local phase shifts across song sections with circular wraparound`() {
        // Construir flujo de 30 s a 120 BPM (periodo = 500 ms):
        // - 0..15 s: pulsos en fase = 100 ms
        // - 15..30 s: pulsos desplazados a fase = 270 ms
        val hopMs = 10.0
        val totalFrames = 3000 // 30 s
        val flux = FloatArray(totalFrames)
        val beatPeriodMs = 500.0

        var t1 = 100.0
        while (t1 < 15_000.0) {
            val f = (t1 / hopMs).toInt()
            if (f in 1 until totalFrames - 1) {
                flux[f] = 1.0f
                flux[f - 1] = 0.35f
                flux[f + 1] = 0.35f
            }
            t1 += beatPeriodMs
        }
        var t2 = 15_270.0
        while (t2 < 29_900.0) {
            val f = (t2 / hopMs).toInt()
            if (f in 1 until totalFrames - 1) {
                flux[f] = 1.0f
                flux[f - 1] = 0.35f
                flux[f + 1] = 0.35f
            }
            t2 += beatPeriodMs
        }

        val segments = AudioChartAnalyzer.estimateSegmentedBeatPhases(
            flux = flux,
            hopDurationMs = hopMs,
            beatPeriodMs = beatPeriodMs,
            startOffsetMs = 0L,
            globalBeatOffsetMs = 100L
        )
        assertTrue("Debe generar múltiples segmentos en una pista de 30 s (obtenidos: ${segments.size})", segments.size >= 3)

        fun circularErrMs(a: Long, b: Long, period: Long): Long {
            val d = abs((a % period) - (b % period))
            return minOf(d, period - d)
        }

        val phaseEarly = AudioChartAnalyzer.localBeatOffsetAt(5_000L, segments, 100L, beatPeriodMs)
        val phaseLate = AudioChartAnalyzer.localBeatOffsetAt(25_000L, segments, 100L, beatPeriodMs)

        assertTrue(
            "La fase en el tramo 0..15 s ($phaseEarly ms) debe acercarse a ~100 ms",
            circularErrMs(phaseEarly, 100L, 500L) <= 30L
        )
        assertTrue(
            "La fase en el tramo 15..30 s ($phaseLate ms) debe adaptarse al cambio a ~270 ms",
            circularErrMs(phaseLate, 270L, 500L) <= 30L
        )

        // Verificar interpolación circular a través del límite 490 ms -> 20 ms (arco corto = +30 ms -> punto medio = 5 ms)
        val wrapSegs = listOf(
            BeatPhaseSegment(centerTimeMs = 10_000L, beatOffsetMs = 490L),
            BeatPhaseSegment(centerTimeMs = 20_000L, beatOffsetMs = 20L)
        )
        val midWrap = AudioChartAnalyzer.localBeatOffsetAt(15_000L, wrapSegs, 0L, 500.0)
        assertTrue(
            "La interpolación circular entre 490 ms y 20 ms debe pasar por ~5 ms, no por 255 ms (obtenido: $midWrap)",
            circularErrMs(midWrap, 5L, 500L) <= 5L
        )
    }

    @Test
    fun `tonal contour lane assignment moves right on rising pitch left on falling pitch and avoids rapid repeats`() {
        fun melodicCand(timeMs: Long, hz: Float) = OnsetCandidate(
            timeMs = timeMs,
            strength = 0.85f,
            dominantBand = 2,
            secondaryBand = null,
            sustainDurationMs = 0L,
            percussiveStrength = 0.2f,
            melodicStrength = 0.9f,
            isPercussiveAccent = false,
            spectralCentroidHz = hz
        )

        // Melodía ascendente: 200 Hz -> 260 Hz -> 340 Hz -> 460 Hz -> debe subir carriles hacia la derecha
        val l0 = AudioChartAnalyzer.assignContourLane(melodicCand(500L, 200f), prevLane = -1, prevCentroidHz = 0f, timeSinceLastMs = 500L)
        val l1 = AudioChartAnalyzer.assignContourLane(melodicCand(900L, 260f), prevLane = l0, prevCentroidHz = 200f, timeSinceLastMs = 400L)
        val l2 = AudioChartAnalyzer.assignContourLane(melodicCand(1300L, 340f), prevLane = l1, prevCentroidHz = 260f, timeSinceLastMs = 400L)
        val l3 = AudioChartAnalyzer.assignContourLane(melodicCand(1700L, 460f), prevLane = l2, prevCentroidHz = 340f, timeSinceLastMs = 400L)
        assertEquals(0, l0)
        assertTrue("Al subir tono el carril debe moverse a la derecha ($l0 -> $l1 -> $l2 -> $l3)", l1 > l0 && l2 > l1 && l3 > l2)

        // Melodía descendente: 460 Hz -> 340 Hz -> 260 Hz -> 200 Hz -> debe bajar carriles hacia la izquierda
        val d1 = AudioChartAnalyzer.assignContourLane(melodicCand(2100L, 340f), prevLane = l3, prevCentroidHz = 460f, timeSinceLastMs = 400L)
        val d2 = AudioChartAnalyzer.assignContourLane(melodicCand(2500L, 260f), prevLane = d1, prevCentroidHz = 340f, timeSinceLastMs = 400L)
        val d3 = AudioChartAnalyzer.assignContourLane(melodicCand(2900L, 200f), prevLane = d2, prevCentroidHz = 260f, timeSinceLastMs = 400L)
        assertTrue("Al bajar tono el carril debe moverse a la izquierda ($l3 -> $d1 -> $d2 -> $d3)", d1 < l3 && d2 < d1 && d3 < d2)

        // Notas rápidas (< 260 ms) del mismo tono jamás repiten el mismo carril
        val rapidSame = AudioChartAnalyzer.assignContourLane(melodicCand(3080L, 200f), prevLane = d3, prevCentroidHz = 200f, timeSinceLastMs = 180L)
        assertNotEquals("Las notas rápidas (< 260 ms) no deben repetir el mismo carril", d3, rapidSame)

        // Nota rápida subiendo cuando ya estamos en el extremo derecho (carril 3) tampoco repite carril 3
        val rapidEdge = AudioChartAnalyzer.assignContourLane(melodicCand(3260L, 900f), prevLane = 3, prevCentroidHz = 600f, timeSinceLastMs = 150L)
        assertNotEquals("En borde derecho con nota rápida no debe repetir el mismo carril", 3, rapidEdge)
    }

    @Test
    fun `dynamic RMS noise gate prevents phantom notes in silent or low-level hiss intros across all 5 difficulties`() {
        val sr = 22050
        val durationSec = 16
        val samples = FloatArray(sr * durationSec)
        val rng = Random(1234L)

        // 0..4.0 s: ruido de fondo suave de compresión MP3 (amplitud = 0.008) con ligeras fluctuaciones
        val introEndIdx = sr * 4
        for (i in 0 until introEndIdx) {
            val env = if ((i / 1100) % 2 == 0) 0.008f else 0.003f
            samples[i] = ((rng.nextDouble() * 2.0 - 1.0) * env).toFloat()
        }

        // 4.0..16.0 s: música activa a 120 BPM con buena amplitud (0.65)
        var tBeat = 4.2
        while (tBeat < durationSec - 0.5) {
            val startIdx = (tBeat * sr).toInt()
            for (k in 0 until (sr * 0.25).toInt()) {
                val idx = startIdx + k
                if (idx >= samples.size) break
                val lt = k.toDouble() / sr.toDouble()
                val kickEnv = exp(-lt * 24.0)
                val synthEnv = exp(-lt * 6.0)
                samples[idx] = (sin(2.0 * PI * 110.0 * lt) * 0.45 * kickEnv + sin(2.0 * PI * 523.25 * lt) * 0.35 * synthEnv).toFloat()
            }
            tBeat += 0.5
        }

        val analyzed = AudioChartAnalyzer.analyzePcmSamples(samples, sr, durationSec * 1000L)
        var prevCount = 0
        for (diff in DifficultyLevel.entries) {
            val chart = analyzed.chartsByDifficulty[diff]!!
            val phantomIntroNotes = chart.filter { it.timeMs < 3850L }
            assertEquals(
                "No debe haber teclas fantasma en la intro con ruido suave en ${diff.displayName} (encontradas: $phantomIntroNotes)",
                0,
                phantomIntroNotes.size
            )
            assertTrue(
                "${diff.displayName} (${chart.size}) debe tener más notas que la anterior ($prevCount)",
                chart.size > prevCount
            )
            prevCount = chart.size
            assertPhonePlayabilityInvariants(chart, diff, "noise-gate ${diff.displayName}")
        }
    }

    @Test
    fun `all 4 visual style token sets provide distinct foreground and surface contrast`() {
        for (style in AppVisualStyle.entries) {
            val t = tokensForStyle(style)
            assertNotEquals("actionPrimaryBg y actionPrimaryText deben contrastar en ${style.displayName}", t.actionPrimaryBg, t.actionPrimaryText)
            assertNotEquals("actionSecondaryBg y actionSecondaryText deben contrastar en ${style.displayName}", t.actionSecondaryBg, t.actionSecondaryText)
            assertNotEquals("textPrimary y cardSurface deben contrastar en ${style.displayName}", t.textPrimary, t.cardSurface)
        }
    }

    @Test
    fun `AppUpdateChecker compares semver tags accurately and targets OpenTiles GitHub repo`() {
        assertEquals("abel12345emilianoesc/OpenTiles", AppUpdateChecker.DEFAULT_GITHUB_REPO)
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("v1.0.1", "1.0"))
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("v1.0.1", "1.0.0"))
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("v1.0.12", "1.0.2"))
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("v2.0.0", "1.9.99"))
        assertFalse(AppUpdateChecker.isRemoteVersionNewer("v1.0.0", "1.0"))
        assertFalse(AppUpdateChecker.isRemoteVersionNewer("v1.0.5", "1.0.5"))
        assertFalse(AppUpdateChecker.isRemoteVersionNewer("v1.0.3", "1.0.5"))
        assertEquals("1.0.42", AppUpdateChecker.normalizeVersion("v1.0.42-beta"))
    }
}
