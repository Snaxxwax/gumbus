package com.cheeseschool.game.data

import kotlinx.coroutines.flow.Flow

class EscapeRepository(private val dao: EscapeRecordDao) {
    val shortestEscapeTime: Flow<Float?> = dao.getShortestEscapeTime()

    suspend fun getShortestEscapeTimeSync(): Float? = dao.getShortestEscapeTimeSync()

    suspend fun recordEscape(escapeTimeSeconds: Float): Boolean {
        val previousBest = dao.getShortestEscapeTimeSync()
        dao.insertRecord(EscapeRecord(escapeTimeSeconds = escapeTimeSeconds))
        return previousBest == null || escapeTimeSeconds < previousBest
    }
}
