package com.codex.remote.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.codex.remote.R

internal enum class ThemeMode(val label: Int) {
    SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark);

    fun isDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal object AppAppearance {
    private const val KEY = "theme"
    private fun preferences(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun selected(context: Context): ThemeMode = selected(preferences(context))
    private fun selected(preferences: SharedPreferences) =
        ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY, null) } ?: ThemeMode.SYSTEM

    fun select(context: Context, mode: ThemeMode) {
        preferences(context).edit().putString(KEY, mode.name).apply()
    }

    @Composable
    fun current(): ThemeMode {
        val context = LocalContext.current
        val preferences = remember(context) { preferences(context) }
        var mode by remember(preferences) { mutableStateOf(selected(preferences)) }
        DisposableEffect(preferences) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY || key == null) mode = selected(preferences)
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            mode = selected(preferences)
            onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return mode
    }

    @Composable
    fun isDark() = current().isDark(isSystemInDarkTheme())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSetting() {
    val context = LocalContext.current
    val selected = AppAppearance.current()
    Text(uiText(R.string.theme_title, "Appearance"), style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(selected = selected == mode, onClick = { AppAppearance.select(context, mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                modifier = Modifier.weight(1f).testTag("app-theme-${mode.name.lowercase(java.util.Locale.ROOT)}")) {
                Text(uiText(mode.label, mode.name))
            }
        }
    }
}
