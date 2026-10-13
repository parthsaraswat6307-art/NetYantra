package com.example.netyantra.data

import com.example.netyantra.decision.RoutingPreference
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing and persisting network settings and states.
 */
interface NetworkRepository {
    fun getSavedPreference(): Flow<RoutingPreference>
    suspend fun savePreference(preference: RoutingPreference)
}
