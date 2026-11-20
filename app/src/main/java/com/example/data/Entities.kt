package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val bpm: Int,
    val genreTag: String,
    val isImported: Boolean,
    val audioFilePath: String,
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "charts",
    primaryKeys = ["songId", "difficultyLevel"],
    indices = [Index("songId")]
)
data class ChartEntity(
    val songId: String,
    val difficultyLevel: Int, // 1..5
    val serializedNotes: String,
    val noteCount: Int,
    val holdCount: Int,
    val chordCount: Int
)

@Entity(
    tableName = "scores",
    primaryKeys = ["songId", "difficultyLevel"],
    indices = [Index("songId")]
)
data class ScoreRecordEntity(
    val songId: String,
    val difficultyLevel: Int, // 1..5
    val highScore: Int,
    val bestAccuracy: Float,  // 0f..100f
    val stars: Int,           // 0..3
    val bestRank: String,     // S, A, B, C
    val maxCombo: Int,
    val playCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "shop_items")
data class ShopItemEntity(
    @PrimaryKey val id: String,
    val category: String,     // THEME, SFX, TRAIL
    val title: String,
    val subtitle: String,
    val price: Int,
    val isUnlocked: Boolean,
    val isEquipped: Boolean,
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val sortOrder: Int = 0
)
