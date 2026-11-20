package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // --- Canciones ---
    @Query("SELECT * FROM songs ORDER BY isImported DESC, createdAt ASC")
    fun observeAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): SongEntity?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongById(songId: String)

    // --- Partituras (5 dificultades por canción) ---
    @Query("SELECT * FROM charts WHERE songId = :songId ORDER BY difficultyLevel ASC")
    fun observeChartsForSong(songId: String): Flow<List<ChartEntity>>

    @Query("SELECT * FROM charts WHERE songId = :songId AND difficultyLevel = :difficultyLevel LIMIT 1")
    suspend fun getChart(songId: String, difficultyLevel: Int): ChartEntity?

    @Query("SELECT * FROM charts")
    fun observeAllCharts(): Flow<List<ChartEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharts(charts: List<ChartEntity>)

    @Query("DELETE FROM charts WHERE songId = :songId")
    suspend fun deleteChartsForSong(songId: String)

    @Query("SELECT SUM(holdCount) FROM charts WHERE songId LIKE 'builtin_%'")
    suspend fun getBuiltInHoldTotal(): Int?

    // --- Puntuaciones y Estrellas ---
    @Query("SELECT * FROM scores")
    fun observeAllScores(): Flow<List<ScoreRecordEntity>>

    @Query("SELECT * FROM scores WHERE songId = :songId AND difficultyLevel = :difficultyLevel LIMIT 1")
    suspend fun getScoreRecord(songId: String, difficultyLevel: Int): ScoreRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScoreRecord(record: ScoreRecordEntity)

    @Query("DELETE FROM scores WHERE songId = :songId")
    suspend fun deleteScoresForSong(songId: String)

    // --- Tienda ---
    @Query("SELECT * FROM shop_items ORDER BY sortOrder ASC")
    fun observeShopItems(): Flow<List<ShopItemEntity>>

    @Query("SELECT * FROM shop_items ORDER BY sortOrder ASC")
    suspend fun getShopItemsOnce(): List<ShopItemEntity>

    @Query("SELECT * FROM shop_items WHERE id = :itemId LIMIT 1")
    suspend fun getShopItemById(itemId: String): ShopItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShopItems(items: List<ShopItemEntity>)

    @Query("UPDATE shop_items SET isUnlocked = 1 WHERE id = :itemId")
    suspend fun unlockShopItem(itemId: String)

    @Query("UPDATE shop_items SET isEquipped = CASE WHEN id = :itemId THEN 1 ELSE 0 END WHERE category = :category")
    suspend fun equipShopItemInCategory(itemId: String, category: String)

    suspend fun deleteSongCompletely(songId: String) {
        deleteChartsForSong(songId)
        deleteScoresForSong(songId)
        deleteSongById(songId)
    }
}
