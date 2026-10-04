package com.epicenter.hifi.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FavoritesRepository(context: Context) {
    private val preferences = context.getSharedPreferences("epicenter_favorites", Context.MODE_PRIVATE)
    private val _favoriteIds = MutableStateFlow(preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    fun toggleFavorite(trackId: String) {
        val updated = _favoriteIds.value.toMutableSet().apply {
            if (!add(trackId)) remove(trackId)
        }
        preferences.edit().putStringSet(KEY_FAVORITES, updated.toSet()).apply()
        _favoriteIds.value = updated
    }

    companion object {
        private const val KEY_FAVORITES = "track_ids"
    }
}
