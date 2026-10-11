package com.codex.remote

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.codex.remote.data.local.PhoneFiles
import com.codex.remote.ui.*
import com.codex.remote.ui.screens.MarkdownBody
import com.codex.remote.ui.screens.RevealTextView
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AppearanceDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun contrast(foreground: Color, background: Color) =
        ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }

    @Test fun themeSelectionUpdatesInheritedContentAndSystemBarsWithoutLosingDraft() {
        val original = AppAppearance.selected(rule.activity)
        var systemDark by mutableStateOf(false)
        var foreground = Color.Unspecified
        var palette: ColorScheme? = null
        rule.setContent {
            val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CodexRemoteTheme {
                    foreground = LocalContentColor.current
                    palette = MaterialTheme.colorScheme
                    var draft by remember { mutableStateOf("") }
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        AppearanceSetting()
                        LanguageSetting()
                        Text("0 B", Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp))
                        OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                        MarkdownBody("# Heading\n\n[Link](https://example.com) and `code`\n\n> Quote\n\n\$x^2\$")
                    }
                }
            }
        }
        try {
            rule.onNodeWithTag("draft").performTextInput("unsent draft")
            for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
                rule.onNodeWithTag("app-theme-${mode.name.lowercase()}").performScrollTo().performClick()
                rule.onNodeWithTag("app-theme-${mode.name.lowercase()}").assertIsSelected()
                for (night in listOf(false, true)) {
                    rule.runOnIdle { systemDark = night }
                    rule.runOnIdle {
                        val colors = palette!!
                        val dark = mode.isDark(night)
                        assertEquals(mode, AppAppearance.selected(rule.activity))
                        assertEquals(colors.onBackground, foreground)
                        assertTrue(contrast(foreground, colors.background) >= 4.5)
                        assertTrue(contrast(foreground, colors.surfaceVariant) >= 4.5)
                        for (background in listOf(colors.background, colors.surface, colors.surfaceVariant, colors.surfaceContainerHigh)) {
                            for (text in listOf(colors.onSurface, colors.onSurfaceVariant, colors.secondary, colors.error, colors.tertiary)) {
                                assertTrue("Unreadable $text on $background", contrast(text, background) >= 4.5)
                            }
                        }
                        for (tint in listOf(colors.secondary, colors.error)) {
                            assertTrue(contrast(colors.onSurface, tint.copy(alpha = 0.14f).compositeOver(colors.surface)) >= 4.5)
                        }
                        val bars = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                        assertEquals(!dark, bars.isAppearanceLightStatusBars)
                        assertEquals(!dark, bars.isAppearanceLightNavigationBars)
                        val markdown = rule.activity.window.decorView.descendants().filterIsInstance<RevealTextView>().single()
                        assertEquals(colors.onSurface.toArgb(), markdown.currentTextColor)
                        assertEquals(colors.secondary.toArgb(), markdown.linkTextColors.defaultColor)
                    }
                    rule.onNodeWithTag("draft").assertTextContains("unsent draft")
                }
            }
        } finally { rule.runOnIdle { AppAppearance.select(rule.activity, original) } }
    }

    @Test fun editorChangesColorsInPlaceAndKeepsUnsavedTextAndSelection() {
        val folder = File(rule.activity.cacheDir, "appearance-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, "note.txt").apply { writeText("original") }
        var dark by mutableStateOf(false)
        var palette: ColorScheme? = null
        rule.setContent { CodexRemoteTheme(darkTheme = dark) {
            palette = MaterialTheme.colorScheme
            FileManagerScreen(PhoneFiles(rule.activity, folder.path), folder.path, file.path) {}
        } }
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("file-editor").fetchSemanticsNodes().isNotEmpty() }
            lateinit var editor: EditText
            val root = (rule.onNodeWithTag("file-editor").fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
            rule.runOnIdle {
                editor = root.rootView.descendants().filterIsInstance<EditText>().single()
                editor.setText("unsaved edit")
                editor.setSelection(4)
            }
            for (night in listOf(true, false)) {
                rule.runOnIdle { dark = night }
                rule.runOnIdle {
                    assertEquals("unsaved edit", editor.text.toString())
                    assertEquals(4, editor.selectionStart)
                    assertEquals(palette!!.onSurface.toArgb(), editor.currentTextColor)
                    assertEquals(palette!!.surface.toArgb(), (editor.background as ColorDrawable).color)
                    assertEquals("original", file.readText())
                }
            }
        } finally { file.delete(); folder.delete() }
    }
}
