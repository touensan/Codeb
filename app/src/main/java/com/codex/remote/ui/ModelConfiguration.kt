package com.codex.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.remote.R
import com.codex.remote.domain.RemoteModel

internal fun String.displayEffort(): String = when (lowercase()) {
    "none" -> uiText(R.string.ui_239, "None")
    "minimal" -> uiText(R.string.ui_240, "Minimal")
    "low" -> uiText(R.string.ui_241, "Low")
    "medium" -> uiText(R.string.ui_242, "Medium")
    "high" -> uiText(R.string.ui_243, "High")
    "xhigh" -> uiText(R.string.ui_244, "Extra high")
    "max" -> "Max"
    "ultra" -> "Ultra"
    else -> this
}

internal fun effortDescription(value: String, fallback: String = ""): String = when (value.lowercase()) {
    "low" -> uiText(R.string.effort_low_detail, "Fast responses with lighter reasoning")
    "medium" -> uiText(R.string.effort_medium_detail, "Balances speed and reasoning depth")
    "high" -> uiText(R.string.effort_high_detail, "Greater reasoning depth for complex problems")
    "xhigh" -> uiText(R.string.effort_xhigh_detail, "Extra reasoning depth for difficult problems")
    "max" -> uiText(R.string.effort_max_detail, "Maximum reasoning depth")
    "ultra" -> uiText(R.string.effort_ultra_detail, "Maximum reasoning with task delegation")
    else -> fallback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelConfigurationSheet(
    models: List<RemoteModel>,
    selectedModel: String?,
    effort: String?,
    serviceTier: String? = null,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onTier: (String?) -> Unit = {},
    onDismiss: () -> Unit,
    providerSelector: @Composable () -> Unit = {},
) {
    val model = models.firstOrNull { it.id == selectedModel }
    var effortMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background) {
        Text(uiText(R.string.configuration, "Configuration"), Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp),
            style = MaterialTheme.typography.titleLarge)
        providerSelector()
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp).padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            items(models, key = { it.id }) { candidate ->
                Row(Modifier.fillMaxWidth().clickable { onModel(candidate.id) }.padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(candidate.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (candidate.id == selectedModel) Icon(Icons.Outlined.Check, null, Modifier.size(24.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.padding(horizontal = 24.dp)) {
            ConfigurationRow(uiText(R.string.ui_193, "Reasoning"), effort?.displayEffort() ?: uiText(R.string.ui_262, "Default reasoning"),
                enabled = model?.supportedReasoningEfforts?.isNotEmpty() == true, onClick = { effortMenu = true })
            DropdownMenu(effortMenu, { effortMenu = false }, Modifier.widthIn(max = 300.dp)) {
                model?.supportedReasoningEfforts.orEmpty().forEach { option ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(option.value.displayEffort())
                            Text(effortDescription(option.value, option.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, onClick = { onEffort(option.value); effortMenu = false },
                        trailingIcon = { if (option.value == effort) Icon(Icons.Outlined.Check, null) })
                }
            }
        }
        if (model?.supportedReasoningEfforts.isNullOrEmpty()) Text(uiText(R.string.capabilities_unknown, "Reasoning options not reported"),
            Modifier.padding(horizontal = 40.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model?.serviceTiers?.isNotEmpty() == true) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.padding(horizontal = 24.dp)) {
                ConfigurationRow(uiText(R.string.ui_197, "Speed"), model.serviceTiers.firstOrNull { it.id == serviceTier }?.let {
                    if (it.id.equals("fast", true)) uiText(R.string.ui_195, "Fast") else it.name
                } ?: uiText(R.string.ui_198, "Standard")) { speedMenu = true }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    DropdownMenuItem(text = { Text(uiText(R.string.ui_198, "Standard")) }, onClick = { onTier(null); speedMenu = false },
                        trailingIcon = { if (serviceTier == null) Icon(Icons.Outlined.Check, null) })
                    model.serviceTiers.forEach { tier ->
                        DropdownMenuItem(text = { Text(if (tier.id.equals("fast", true)) uiText(R.string.ui_195, "Fast") else tier.name) }, onClick = { onTier(tier.id); speedMenu = false },
                            trailingIcon = { if (tier.id == serviceTier) Icon(Icons.Outlined.Check, null) })
                    }
                }
            }
        }
        Button(onClick = onDismiss, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(24.dp).height(52.dp)) {
            Text(uiText(R.string.done, "Done"), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun ConfigurationRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(value, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(20.dp))
    }
}
