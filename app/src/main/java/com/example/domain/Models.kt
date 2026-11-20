package com.example.domain

import androidx.compose.ui.graphics.Color

/**
 * Estilos visuales generales de toda la aplicación configurables desde Configuración.
 */
enum class AppVisualStyle(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val isDark: Boolean,
    val previewPrimaryHex: Long,
    val previewBackgroundHex: Long
) {
    NEON_VOID(
        id = "style_neon_void",
        displayName = "Resonancia Oscura",
        subtitle = "Espacio profundo azulado con cristal neón (Original)",
        isDark = true,
        previewPrimaryHex = 0xFF8B5CF6,
        previewBackgroundHex = 0xFF0B0F19
    ),
    OLED_PURE(
        id = "style_oled_pure",
        displayName = "OLED Puro",
        subtitle = "Negro absoluto #000000 para máximo contraste y ahorro AMOLED",
        isDark = true,
        previewPrimaryHex = 0xFF06B6D4,
        previewBackgroundHex = 0xFF000000
    ),
    SYNTHWAVE_DUSK(
        id = "style_synthwave_dusk",
        displayName = "Púrpura Synthwave",
        subtitle = "Atmósfera retro-futurista índigo y destellos magenta",
        isDark = true,
        previewPrimaryHex = 0xFFF43F5E,
        previewBackgroundHex = 0xFF130926
    ),
    FROST_LIGHT(
        id = "style_frost_light",
        displayName = "Claro Cristal",
        subtitle = "Superficies luminosas de alto contraste para exteriores",
        isDark = false,
        previewPrimaryHex = 0xFF6D28D9,
        previewBackgroundHex = 0xFFF1F5FB
    );

    companion object {
        fun fromId(id: String): AppVisualStyle =
            entries.firstOrNull { it.id == id } ?: NEON_VOID
    }
}

/**
 * Tipos de teclas disponibles para dar variedad rítmica y visual a cada canción.
 */
enum class NoteKind(val code: Int, val label: String) {
    TAP(0, "Toque"),
    HOLD(1, "Mantener"),
    CHORD(2, "Acorde"),
    BEAT_PULSE(3, "Pulso");

    companion object {
        fun fromCode(code: Int): NoteKind =
            entries.firstOrNull { it.code == code } ?: TAP
    }
}

/**
 * Las 5 dificultades del juego.
 * A mayor dificultad, mayor es la fidelidad rítmica y melódica respecto a la canción real.
 */
enum class DifficultyLevel(
    val level: Int,
    val displayName: String,
    val fidelityLabel: String,
    val description: String,
    val minIntervalMs: Long,
    val fallDurationMs: Long,
    val energyPercentileThreshold: Float,
    val allowChords: Boolean,
    val chordIntensityThreshold: Float,
    val holdMinDurationMs: Long,
    val scoreMultiplier: Float
) {
    SUAVE(
        level = 1,
        displayName = "Suave",
        fidelityLabel = "Pulso Esencial (25% fidelidad)",
        description = "Solo los acentos principales del compás con teclas sostenidas amplias.",
        minIntervalMs = 430L,
        fallDurationMs = 1550L,
        energyPercentileThreshold = 0.78f,
        allowChords = false,
        chordIntensityThreshold = 1.0f,
        holdMinDurationMs = 420L,
        scoreMultiplier = 1.0f
    ),
    RITMO(
        level = 2,
        displayName = "Ritmo",
        fidelityLabel = "Base y Acentos (45% fidelidad)",
        description = "Sigue el ritmo principal y los inicios de frase melódica con teclas de mantener.",
        minIntervalMs = 310L,
        fallDurationMs = 1320L,
        energyPercentileThreshold = 0.62f,
        allowChords = false,
        chordIntensityThreshold = 1.0f,
        holdMinDurationMs = 320L,
        scoreMultiplier = 1.25f
    ),
    FLUIDO(
        level = 3,
        displayName = "Fluido",
        fidelityLabel = "Melodía Activa (68% fidelidad)",
        description = "Incorpora síncopas, teclas de pulso, sostenidas expresivas y primeros acordes.",
        minIntervalMs = 215L,
        fallDurationMs = 1120L,
        energyPercentileThreshold = 0.46f,
        allowChords = true,
        chordIntensityThreshold = 0.88f,
        holdMinDurationMs = 260L,
        scoreMultiplier = 1.5f
    ),
    INTENSO(
        level = 4,
        displayName = "Intenso",
        fidelityLabel = "Armonía Completa (85% fidelidad)",
        description = "Alta densidad fiel a la percusión y voz/instrumento líder, con acordes y sostenidas.",
        minIntervalMs = 145L,
        fallDurationMs = 940L,
        energyPercentileThreshold = 0.30f,
        allowChords = true,
        chordIntensityThreshold = 0.76f,
        holdMinDurationMs = 220L,
        scoreMultiplier = 1.85f
    ),
    RESONANCIA_MAESTRA(
        level = 5,
        displayName = "Resonancia Maestra",
        fidelityLabel = "Transcripción Total (100% fidelidad)",
        description = "Experiencia de concierto real: cada subdivisión, trino, acorde y nota sostenida de la canción.",
        minIntervalMs = 95L,
        fallDurationMs = 790L,
        energyPercentileThreshold = 0.14f,
        allowChords = true,
        chordIntensityThreshold = 0.64f,
        holdMinDurationMs = 190L,
        scoreMultiplier = 2.3f
    );

    companion object {
        fun fromLevel(level: Int): DifficultyLevel =
            entries.firstOrNull { it.level == level } ?: FLUIDO
    }
}

/**
 * Representa una tecla individual, sostenida, acorde o de pulso fuerte dentro de uno de los 4 carriles (0..3).
 */
data class RhythmNote(
    val id: Int,
    val timeMs: Long,
    val durationMs: Long = 0L, // > 0 indica tecla sostenida (Hold)
    val lane: Int,             // 0, 1, 2 o 3
    val isChord: Boolean = false,
    val intensity: Float = 0.7f,
    val frequencyBand: Int = lane,
    val kind: NoteKind = when {
        durationMs >= 180L -> NoteKind.HOLD
        isChord -> NoteKind.CHORD
        intensity >= 0.86f -> NoteKind.BEAT_PULSE
        else -> NoteKind.TAP
    }
) {
    val isHold: Boolean get() = durationMs >= 180L || kind == NoteKind.HOLD
    val isBeatAccent: Boolean get() = kind == NoteKind.BEAT_PULSE
    val endTimeMs: Long get() = timeMs + durationMs
}

/**
 * Juicio de precisión al tocar una tecla respecto a la línea de impacto.
 */
enum class HitJudgment(
    val displayText: String,
    val windowMs: Long,
    val baseScore: Int,
    val accuracyWeight: Float,
    val colorHex: Long
) {
    PERFECT("¡PERFECTO!", 54L, 300, 1.0f, 0xFF06B6D4),
    GREAT("¡GENIAL!", 108L, 200, 0.75f, 0xFF8B5CF6),
    GOOD("BIEN", 158L, 100, 0.45f, 0xFFFFC83D),
    MISS("FALLO", 215L, 0, 0.0f, 0xFFF43F5E)
}

enum class ShopCategory(val id: String, val displayName: String) {
    TILE_THEME("THEME", "Tonalidades de Teclas"),
    SFX_PACK("SFX", "Efectos de Sonido"),
    HIT_TRAIL("TRAIL", "Estelas de Impacto");

    companion object {
        fun fromId(id: String): ShopCategory =
            entries.firstOrNull { it.id == id } ?: TILE_THEME
    }
}

/**
 * Paleta visual equipada para las teclas, carriles y modo fiebre.
 */
data class TileThemePalette(
    val id: String,
    val name: String,
    val subtitle: String,
    val price: Int,
    val tileTopColor: Color,
    val tileBottomColor: Color,
    val glowColor: Color,
    val sustainColor: Color,
    val feverColor: Color
)

enum class SfxProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val price: Int,
    val baseFreqsHz: FloatArray
) {
    CRISTAL_ETEREO(
        id = "sfx_crystal",
        displayName = "Cristal Etéreo",
        subtitle = "Armónicos puros de cuarzo con resonancia brillante",
        price = 0,
        baseFreqsHz = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.50f)
    ),
    PIANO_COLA(
        id = "sfx_piano",
        displayName = "Piano de Concierto",
        subtitle = "Ataque cálido de martillo acústico con caída natural",
        price = 180,
        baseFreqsHz = floatArrayOf(261.63f, 329.63f, 392.00f, 523.25f)
    ),
    MARIMBA_NEON(
        id = "sfx_marimba",
        displayName = "Marimba de Neón",
        subtitle = "Madera resonante cálida con golpe percusivo definido",
        price = 210,
        baseFreqsHz = floatArrayOf(349.23f, 440.00f, 523.25f, 698.46f)
    ),
    SINTE_RETRO(
        id = "sfx_synth",
        displayName = "Sinte Cyberpunk",
        subtitle = "Pulso analógico rico en armónicos neon-wave",
        price = 240,
        baseFreqsHz = floatArrayOf(293.66f, 369.99f, 440.00f, 587.33f)
    ),
    CAMPANA_ASTRAL(
        id = "sfx_astral_bell",
        displayName = "Campana Astral",
        subtitle = "Timbre metálico celeste con cola cristalina espacial",
        price = 260,
        baseFreqsHz = floatArrayOf(587.33f, 739.99f, 880.00f, 1174.66f)
    ),
    PERCUSION_LASER(
        id = "sfx_laser",
        displayName = "Percusión Galáctica",
        subtitle = "Transitorios ultra cortos de alta precisión rítmica",
        price = 300,
        baseFreqsHz = floatArrayOf(440.0f, 554.37f, 659.25f, 880.0f)
    ),
    BAJO_SUB_PULSE(
        id = "sfx_sub_pulse",
        displayName = "Bajo Sub-Pulse",
        subtitle = "Impacto grave profundo con pegada electrónica club",
        price = 320,
        baseFreqsHz = floatArrayOf(130.81f, 164.81f, 196.00f, 261.63f)
    ),
    CHIPTUNE_8BIT(
        id = "sfx_chiptune",
        displayName = "Chiptune 8-Bit",
        subtitle = "Onda cuadrada retro estilo arcade clásico de consola",
        price = 360,
        baseFreqsHz = floatArrayOf(392.00f, 493.88f, 587.33f, 783.99f)
    );

    companion object {
        fun fromId(id: String): SfxProfile =
            entries.firstOrNull { it.id == id } ?: CRISTAL_ETEREO
    }
}

enum class HitTrailStyle(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val price: Int
) {
    ONDAS_PRISMA(
        id = "trail_prism",
        displayName = "Ondas de Prisma",
        subtitle = "Anillos concéntricos luminosos con chispas suaves",
        price = 0
    ),
    ESTALLIDO_ESTELAR(
        id = "trail_starburst",
        displayName = "Estallido Estelar",
        subtitle = "Destello radial de fragmentos cromáticos de alta velocidad",
        price = 200
    ),
    VORTICE_ESPIRAL(
        id = "trail_spiral_vortex",
        displayName = "Vórtice Espiral",
        subtitle = "Espiral giratoria luminosa con doble órbita energética",
        price = 240
    ),
    PULSO_HEXAGONAL(
        id = "trail_hex",
        displayName = "Pulso Geométrico",
        subtitle = "Haz láser vertical que ilumina todo el carril activo",
        price = 280
    ),
    LLUVIA_CHISPAS(
        id = "trail_spark_rain",
        displayName = "Lluvia de Chispas",
        subtitle = "Estallido ascendente de partículas incandescentes al ritmo",
        price = 310
    ),
    ONDA_SISMICA(
        id = "trail_seismic_wave",
        displayName = "Onda Sísmica",
        subtitle = "Triple anillo expansivo con núcleo blanco de alto impacto",
        price = 380
    );

    companion object {
        fun fromId(id: String): HitTrailStyle =
            entries.firstOrNull { it.id == id } ?: ONDAS_PRISMA
    }
}

object BuiltInCatalog {
    val tileThemes = listOf(
        TileThemePalette(
            id = "theme_neon_resonance",
            name = "Resonancia Neón",
            subtitle = "Cian eléctrico a violeta profundo (Diseño original)",
            price = 0,
            tileTopColor = Color(0xFF06B6D4),
            tileBottomColor = Color(0xFF8B5CF6),
            glowColor = Color(0xFF4CD7F6),
            sustainColor = Color(0xFF06B6D4),
            feverColor = Color(0xFFF43F5E)
        ),
        TileThemePalette(
            id = "theme_crimson_pulse",
            name = "Pulso Carmesí",
            subtitle = "Fuego magenta a rubí incandescente de alto contraste",
            price = 150,
            tileTopColor = Color(0xFFFF6B8B),
            tileBottomColor = Color(0xFFE11D48),
            glowColor = Color(0xFFF43F5E),
            sustainColor = Color(0xFFFB7185),
            feverColor = Color(0xFFFFC83D)
        ),
        TileThemePalette(
            id = "theme_neon_sakura",
            name = "Sakura Neón",
            subtitle = "Pétalos rosa pastel hacia fucsia eléctrico nocturno",
            price = 200,
            tileTopColor = Color(0xFFFBCFE8),
            tileBottomColor = Color(0xFFEC4899),
            glowColor = Color(0xFFF472B6),
            sustainColor = Color(0xFFF9A8D4),
            feverColor = Color(0xFF38BDF8)
        ),
        TileThemePalette(
            id = "theme_emerald_aurora",
            name = "Aurora Esmeralda",
            subtitle = "Menta bioluminiscente a turquesa abisal",
            price = 220,
            tileTopColor = Color(0xFF34D399),
            tileBottomColor = Color(0xFF059669),
            glowColor = Color(0xFF6EE7B7),
            sustainColor = Color(0xFF10B981),
            feverColor = Color(0xFF06B6D4)
        ),
        TileThemePalette(
            id = "theme_amber_sunset",
            name = "Atardecer Ámbar",
            subtitle = "Naranja coral a púrpura crepuscular cálido",
            price = 250,
            tileTopColor = Color(0xFFFB923C),
            tileBottomColor = Color(0xFFC026D3),
            glowColor = Color(0xFFFDBA74),
            sustainColor = Color(0xFFF97316),
            feverColor = Color(0xFFFACC15)
        ),
        TileThemePalette(
            id = "theme_solar_gold",
            name = "Oro Solar",
            subtitle = "Ámbar radiante a bronce imperial para rachas legendarias",
            price = 280,
            tileTopColor = Color(0xFFFBBF24),
            tileBottomColor = Color(0xFFD97706),
            glowColor = Color(0xFFFDE68A),
            sustainColor = Color(0xFFF59E0B),
            feverColor = Color(0xFFF43F5E)
        ),
        TileThemePalette(
            id = "theme_cyber_mint",
            name = "Ciber-Menta",
            subtitle = "Lima fosforescente a aguamarina futurista",
            price = 300,
            tileTopColor = Color(0xFFA3E635),
            tileBottomColor = Color(0xFF0D9488),
            glowColor = Color(0xFFBEF264),
            sustainColor = Color(0xFF2DD4BF),
            feverColor = Color(0xFFF43F5E)
        ),
        TileThemePalette(
            id = "theme_electric_obsidian",
            name = "Obsidiana Eléctrica",
            subtitle = "Cristal grafito oscuro con núcleo azul voltaico minimalista",
            price = 320,
            tileTopColor = Color(0xFF38BDF8),
            tileBottomColor = Color(0xFF0F172A),
            glowColor = Color(0xFF7DD3FC),
            sustainColor = Color(0xFF0EA5E9),
            feverColor = Color(0xFFE879F9)
        ),
        TileThemePalette(
            id = "theme_glacial_prism",
            name = "Prisma Glacial",
            subtitle = "Cristal ártico blanco-azulado de máxima nitidez visual",
            price = 340,
            tileTopColor = Color(0xFFE0F2FE),
            tileBottomColor = Color(0xFF38BDF8),
            glowColor = Color(0xFFBAE6FD),
            sustainColor = Color(0xFF7DD3FC),
            feverColor = Color(0xFFA855F7)
        ),
        TileThemePalette(
            id = "theme_chromatic_spectrum",
            name = "Espectro Hipernova",
            subtitle = "Fusión ultravioleta, fucsia y cian de concierto estelar",
            price = 420,
            tileTopColor = Color(0xFFF472B6),
            tileBottomColor = Color(0xFF6366F1),
            glowColor = Color(0xFFE879F9),
            sustainColor = Color(0xFFC084FC),
            feverColor = Color(0xFF22D3EE)
        ),
        TileThemePalette(
            id = "theme_cosmic_storm",
            name = "Tormenta Cósmica",
            subtitle = "Relámpago índigo a turquesa cuántico de edición maestra",
            price = 480,
            tileTopColor = Color(0xFF22D3EE),
            tileBottomColor = Color(0xFF4F46E5),
            glowColor = Color(0xFF67E8F9),
            sustainColor = Color(0xFF818CF8),
            feverColor = Color(0xFFFF4D8D)
        )
    )

    fun findTheme(id: String): TileThemePalette =
        tileThemes.firstOrNull { it.id == id } ?: tileThemes.first()
}
