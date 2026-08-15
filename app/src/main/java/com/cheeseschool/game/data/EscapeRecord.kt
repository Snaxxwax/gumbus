package com.cheeseschool.game.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "escape_records")
data class EscapeRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val escapeTimeSeconds: Float,
    val timestamp: Long = System.currentTimeMillis()
)
