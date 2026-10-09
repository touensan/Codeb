package com.codex.remote.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.codex.remote.R

object AppLanguage {
    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "").orEmpty()

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val language = selected(context)
        if (language.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language))
        })
    }

    fun select(context: Context, language: String) {
        require(language in setOf("", "zh", "en", "ja"))
        if (selected(context) == language) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        } else {
            context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", language).apply()
            var owner = context
            while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
            (owner as? Activity)?.recreate()
        }
    }
}

@Composable
internal fun LanguageSetting() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("" to uiText(R.string.language_system, "Follow system"), "zh" to "中文", "en" to "English", "ja" to "日本語")
    val selected = AppLanguage.selected(context).substringBefore('-')
    Text(uiText(R.string.ui_273, "Language"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { expanded = true }.testTag("app-language").padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(options.firstOrNull { it.first == selected }?.second ?: options.first().second, Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (tag, label) ->
                DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("app-language-${tag.ifEmpty { "system" }}"),
                    leadingIcon = { RadioButton(selected == tag, onClick = null) }, onClick = {
                        expanded = false
                        AppLanguage.select(context, tag)
                    })
            }
        }
    }
}
