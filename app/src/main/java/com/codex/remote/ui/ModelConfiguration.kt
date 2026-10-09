package com.codex.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.window.PopupProperties
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

@Composable
internal fun ModelConfigurationMenu(
    expanded: Boolean,
    models: List<RemoteModel>,
    selectedModel: String?,
    effort: String?,
    serviceTier: String? = null,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onTier: (String?) -> Unit = {},
    onDismiss: () -> Unit,
    providers: List<Pair<String, String>> = emptyList(),
    providerId: String? = null,
    onProvider: (String) -> Unit = {},
    onAddProvider: () -> Unit = {},
) {
    val model = models.firstOrNull { it.id == selectedModel }
    var page by remember { mutableStateOf("reasoning") }
    var providersExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) page = "reasoning" }
    val speed = model?.serviceTiers?.firstOrNull { it.id == serviceTier }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(280.dp).heightIn(max = 500.dp).testTag("model-configuration-menu"),
        properties = PopupProperties(focusable = false),
        shape = RoundedCornerShape(20.dp),
    ) {
        if (page != "reasoning") {
            DropdownMenuItem(
                text = { Text(when (page) {
                    "model" -> uiText(R.string.ui_191, "Model")
                    else -> uiText(R.string.ui_197, "Speed")
                }) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_002, "Back")) },
                onClick = { page = "reasoning"; providersExpanded = false },
            )
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        }
        when (page) {
            "model" -> {
                if (providers.isNotEmpty()) Box(Modifier.padding(horizontal = 12.dp).testTag("api-provider-selector")) {
                    ConfigurationRow(uiText(R.string.api_provider, "API provider"), providers.firstOrNull { it.first == providerId }?.second.orEmpty()) { providersExpanded = !providersExpanded }
                }
                if (providersExpanded) Column(Modifier.testTag("api-provider-options")) {
                    providers.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name) },
                            trailingIcon = { if (id == providerId) Icon(Icons.Outlined.Check, null) },
                            onClick = { onProvider(id); providersExpanded = false })
                    }
                    DropdownMenuItem(text = { Text(uiText(R.string.ui_261, "Add API provider")) },
                        onClick = { onDismiss(); onAddProvider() })
                } else models.forEach { candidate ->
                    DropdownMenuItem(
                        modifier = Modifier.semantics { selected = candidate.id == selectedModel },
                        text = { Text(candidate.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { if (candidate.id == selectedModel) Icon(Icons.Outlined.Check, null) },
                        onClick = { onModel(candidate.id); page = "reasoning" },
                    )
                }
            }
            "speed" -> {
                DropdownMenuItem(
                    modifier = Modifier.semantics { selected = serviceTier == null },
                    text = { Text(uiText(R.string.ui_198, "Standard")) },
                    trailingIcon = { if (serviceTier == null) Icon(Icons.Outlined.Check, null) },
                    onClick = { onTier(null); onDismiss() },
                )
                model?.serviceTiers.orEmpty().forEach { tier ->
                    DropdownMenuItem(
                        modifier = Modifier.semantics { selected = tier.id == serviceTier },
                        text = {
                            Column {
                                Text(if (tier.id.equals("fast", true)) uiText(R.string.ui_195, "Fast") else tier.name)
                                if (tier.description.isNotBlank()) Text(tier.description,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        trailingIcon = { if (tier.id == serviceTier) Icon(Icons.Outlined.Check, null) },
                        onClick = { onTier(tier.id); onDismiss() },
                    )
                }
            }
            else -> {
                if (!model?.supportedReasoningEfforts.isNullOrEmpty()) Text(uiText(R.string.ui_193, "Reasoning"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                model?.supportedReasoningEfforts.orEmpty().forEach { option ->
                    DropdownMenuItem(
                        modifier = Modifier.semantics { selected = option.value == effort },
                        text = { Text(option.value.displayEffort()) },
                        trailingIcon = { if (option.value == effort) Icon(Icons.Outlined.Check, null) },
                        onClick = { onEffort(option.value); onDismiss() },
                    )
                }
                if (!model?.supportedReasoningEfforts.isNullOrEmpty()) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(uiText(R.string.ui_191, "Model"))
                            Text(model?.displayName ?: selectedModel.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) },
                    onClick = { page = "model" },
                )
                if (model?.serviceTiers?.isNotEmpty() == true) DropdownMenuItem(
                    text = {
                        Column {
                            Text(uiText(R.string.ui_197, "Speed"))
                            Text(if (speed?.id.equals("fast", true)) uiText(R.string.ui_195, "Fast")
                                else speed?.name ?: uiText(R.string.ui_198, "Standard"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) },
                    onClick = { page = "speed" },
                )
            }
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
