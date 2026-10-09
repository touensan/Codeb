package com.codex.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.remote.ui.CodexRemoteApp
import com.codex.remote.ui.theme.CodexRemoteTheme
import com.codex.remote.ui.UiText
import com.codex.remote.data.diagnostics.DebugApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.codex.remote.ui.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiText.context = createConfigurationContext(resources.configuration)
        DebugApi.attach(applicationContext)
        enableEdgeToEdge()
        setContent {
            CodexRemoteTheme {
                val appViewModel: AppViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(application, managerOnly = true) as T
                })
                CodexRemoteApp(appViewModel)
            }
        }
    }
}
