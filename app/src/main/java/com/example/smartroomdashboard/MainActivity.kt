package com.example.smartroomdashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.smartroomdashboard.data.local.SharedPreferencesSettingsStore
import com.example.smartroomdashboard.data.local.SharedPreferencesTodoLocalStore
import com.example.smartroomdashboard.data.remote.HomeAssistantTodoRepository
import com.example.smartroomdashboard.data.security.AndroidKeystoreSecureStorage
import com.example.smartroomdashboard.ocr.OcrEngine
import com.example.smartroomdashboard.ocr.MlKitDigitalInkOcrEngine
import com.example.smartroomdashboard.ui.SmartRoomApp
import com.example.smartroomdashboard.ui.TodoViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<TodoViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val preferences = getSharedPreferences("smart_room_dashboard", MODE_PRIVATE)
                val settings = SharedPreferencesSettingsStore(preferences)
                val secureStorage = AndroidKeystoreSecureStorage(applicationContext)
                return TodoViewModel(
                    repository = HomeAssistantTodoRepository(
                        local = SharedPreferencesTodoLocalStore(preferences),
                        settings = settings,
                        secureStorage = secureStorage,
                    ),
                    settingsStore = settings,
                    secureStorage = secureStorage,
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartRoomApp(viewModel, MlKitDigitalInkOcrEngine())
        }
    }
}
