package com.cheeseschool.game.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EscapeRecordDao {
    @Query("SELECT MIN(escapeTimeSeconds) FROM escape_records")
    fun getShortestEscapeTime(): Flow<Float?>

    @Query("SELECT MIN(escapeTimeSeconds) FROM escape_records")
    suspend fun getShortestEscapeTimeSync(): Float?

    @Query("SELECT * FROM escape_records ORDER BY escapeTimeSeconds ASC")
    fun getAllRecords(): Flow<List<EscapeRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: EscapeRecord): Long
}
