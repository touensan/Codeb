package com.codex.remote.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.codex.remote.BuildConfig
import com.codex.remote.LocalUiState
import com.codex.remote.LocalViewModel
import com.codex.remote.R
import com.codex.remote.data.api.CloudUpdate
import com.codex.remote.data.api.safeCloudLink

private fun openCloudLink(context: android.content.Context, url: String) {
    if (safeCloudLink(url)) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { android.widget.Toast.makeText(context, it.localizedMessage, android.widget.Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun CloudSettings(state: LocalUiState, local: LocalViewModel) {
    val context = LocalContext.current
    var about by remember { mutableStateOf(false) }
    if (state.cloud.recommendationsEnabled && state.cloud.recommendations.isNotEmpty()) {
        Text(uiText(R.string.cloud_recommendations, "Recommended"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("cloud-recommendations"))
        state.cloud.recommendations.forEach { item ->
            Column(Modifier.fillMaxWidth().clickable { openCloudLink(context, item.url) }.padding(vertical = 10.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(12.dp))
    }
    TextButton(onClick = { openCloudLink(context, "https://api.baiyun.si/docs/codeb/") }, modifier = Modifier.fillMaxWidth().testTag("open-codeb-guide")) {
        Text(uiText(R.string.codeb_guide, "Codeb setup guide"), Modifier.weight(1f))
    }
    TextButton(onClick = { about = true; local.refreshCloud(force = true) }, modifier = Modifier.fillMaxWidth().testTag("open-about")) {
        Text(uiText(R.string.cloud_about, "About"), Modifier.weight(1f))
        Text(BuildConfig.VERSION_NAME)
    }
    if (about) AlertDialog(onDismissRequest = { about = false },
        title = { Text(uiText(R.string.cloud_about, "About")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.cloud.about.name, style = MaterialTheme.typography.titleLarge)
            Text(uiText(R.string.cloud_version, "Version %1\$s", BuildConfig.VERSION_NAME))
            if (state.cloud.about.description.isNotBlank()) Text(state.cloud.about.description)
            Text("Apache-2.0 · Codex Remote", style = MaterialTheme.typography.bodySmall)
            if (state.cloud.about.website.isNotBlank()) TextButton(onClick = { openCloudLink(context, state.cloud.about.website) }) { Text(uiText(R.string.cloud_website, "Website")) }
            TextButton(onClick = { openCloudLink(context, "https://github.com/touensan/codex-remote-android") }) { Text(uiText(R.string.cloud_source, "Source and licenses")) }
            HorizontalDivider()
            Button(onClick = { local.refreshCloud(force = true) }, enabled = !state.checkingCloud, modifier = Modifier.testTag("check-updates")) {
                if (state.checkingCloud) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(uiText(R.string.cloud_check_updates, "Check for updates"))
            }
            when {
                state.checkingCloud -> Text(uiText(R.string.cloud_checking, "Checking…"))
                state.cloudError != null -> Text(uiText(R.string.cloud_check_failed, "Check failed: %1\$s", state.cloudError), color = MaterialTheme.colorScheme.error)
                state.cloud.update.available(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE) -> {
                    UpdateDetails(state.cloud.update)
                    Button(onClick = { openCloudLink(context, state.cloud.update.url) }) { Text(uiText(R.string.cloud_download, "Open download")) }
                }
                state.cloudCheckedAt > 0 -> Text(uiText(R.string.cloud_no_update, "No update available for this app"))
            }
        } },
        confirmButton = { TextButton(onClick = { about = false }) { Text(uiText(R.string.ui_151, "Close")) } })
}

@Composable
private fun UpdateDetails(update: CloudUpdate) {
    Text(uiText(R.string.cloud_new_version, "New version: %1\$s", update.versionName), style = MaterialTheme.typography.titleMedium)
    if (update.notes.isNotBlank()) Text(update.notes)
    if (update.sha256.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text("SHA-256: ${update.sha256}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun CloudUpdateNotice(update: CloudUpdate) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("cloud-notices", 0) }
    var dismissed by remember { mutableIntStateOf(preferences.getInt("update", 0)) }
    if (update.available(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE) && update.versionCode > dismissed) {
        fun dismiss() { dismissed = update.versionCode; preferences.edit().putInt("update", dismissed).apply() }
        AlertDialog(onDismissRequest = ::dismiss, title = { Text(uiText(R.string.cloud_update_available, "Update available")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { UpdateDetails(update) } },
            confirmButton = { TextButton(onClick = { openCloudLink(context, update.url); dismiss() }) { Text(uiText(R.string.cloud_download, "Open download")) } },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(uiText(R.string.cloud_later, "Later")) } })
    }
}
