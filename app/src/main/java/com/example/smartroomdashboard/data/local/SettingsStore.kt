package com.example.smartroomdashboard.data.local

import android.content.SharedPreferences
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.DEFAULT_TEXT_SCALE
import com.example.smartroomdashboard.domain.MAX_TEXT_SCALE
import com.example.smartroomdashboard.domain.MIN_TEXT_SCALE
import com.example.smartroomdashboard.domain.normalizedBaseUrl
import com.example.smartroomdashboard.domain.parseBoardEntityIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    suspend fun save(settings: AppSettings)
}

class SharedPreferencesSettingsStore(
    private val preferences: SharedPreferences,
) : SettingsStore {
    private val state = MutableStateFlow(readSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override suspend fun save(settings: AppSettings) {
        val normalized = settings.copy(
            homeAssistantUrl = settings.homeAssistantUrl.normalizedBaseUrl(),
            textScale = settings.textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        )
        preferences.edit()
            .putString(KEY_URL, normalized.homeAssistantUrl)
            .putString(KEY_ENTITY, normalized.todoEntityId)
            .putString(KEY_DASHBOARD, normalized.dashboardPath)
            .putString(KEY_BOARD_ENTITIES, normalized.boardEntityIds.joinToString("\n"))
            .putFloat(KEY_TEXT_SCALE, normalized.textScale)
            .putBoolean(KEY_KEEP_SCREEN_ON, normalized.keepScreenOn)
            .putBoolean(KEY_SETUP_COMPLETED, normalized.setupCompleted)
            .putString(KEY_CLOUD_FALLBACK, normalized.cloudFallbackUrl)
            .apply()
        state.value = normalized
    }

    private fun readSettings() = AppSettings(
        homeAssistantUrl = preferences.getString(KEY_URL, "").orEmpty(),
        todoEntityId = preferences.getString(KEY_ENTITY, "todo.smart_room").orEmpty(),
        dashboardPath = preferences.getString(KEY_DASHBOARD, "lovelace/0").orEmpty(),
        boardEntityIds = parseBoardEntityIds(preferences.getString(KEY_BOARD_ENTITIES, "").orEmpty()),
        textScale = preferences.getFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE)
            .coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, true),
        cloudFallbackUrl = preferences.getString(KEY_CLOUD_FALLBACK, "").orEmpty(),
        // Derive completion for installs that predate the flag so an upgrade
        // never strands a working setup behind the onboarding screen.
        setupCompleted = preferences.getBoolean(
            KEY_SETUP_COMPLETED,
            preferences.getString(KEY_URL, "").orEmpty().isNotBlank(),
        ),
    )

    private companion object {
        const val KEY_URL = "home_assistant_url"
        const val KEY_ENTITY = "todo_entity_id"
        const val KEY_DASHBOARD = "dashboard_path"
        const val KEY_BOARD_ENTITIES = "board_entity_ids"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_CLOUD_FALLBACK = "cloud_fallback_url"
    }
}
