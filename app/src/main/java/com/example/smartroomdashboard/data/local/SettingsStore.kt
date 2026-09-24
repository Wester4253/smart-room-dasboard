package com.example.smartroomdashboard.data.local

import android.content.SharedPreferences
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.normalizedBaseUrl
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
        val normalized = settings.copy(homeAssistantUrl = settings.homeAssistantUrl.normalizedBaseUrl())
        preferences.edit()
            .putString(KEY_URL, normalized.homeAssistantUrl)
            .putString(KEY_ENTITY, normalized.todoEntityId)
            .putString(KEY_DASHBOARD, normalized.dashboardPath)
            .apply()
        state.value = normalized
    }

    private fun readSettings() = AppSettings(
        homeAssistantUrl = preferences.getString(KEY_URL, "").orEmpty(),
        todoEntityId = preferences.getString(KEY_ENTITY, "todo.smart_room").orEmpty(),
        dashboardPath = preferences.getString(KEY_DASHBOARD, "lovelace/0").orEmpty(),
    )

    private companion object {
        const val KEY_URL = "home_assistant_url"
        const val KEY_ENTITY = "todo_entity_id"
        const val KEY_DASHBOARD = "dashboard_path"
    }
}
