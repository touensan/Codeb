package com.codex.remote

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.store.LocalStore
import com.codex.remote.domain.ComposerDefaults
import com.codex.remote.domain.ReasoningEffortOption
import com.codex.remote.domain.RemoteModel
import com.codex.remote.domain.RemoteServiceTier
import com.codex.remote.domain.forModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComposerDefaultsDeviceTest {
    @Test fun newStoreKeepsLastSelectionOutsideConversationCacheAndValidatesCapabilities() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "composer-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getFilesDir(): File = File(target.cacheDir, namespace)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$namespace-$name", mode)
        }
        try {
            val model = RemoteModel("returned-model", "Model", "", true,
                supportedReasoningEfforts = listOf(ReasoningEffortOption("low"), ReasoningEffortOption("high")),
                defaultReasoningEffort = "low", serviceTiers = listOf(RemoteServiceTier("fast", "Fast")))
            val defaults = ComposerDefaults(model.id, "high", "fast", isConfigured = true)
            val original = LocalStore(isolated)
            assertFalse(original.composerDefaults().isConfigured)
            original.saveComposerDefaults(defaults)
            original.clearCache()
            assertTrue(LocalStore(isolated).composerDefaults().isConfigured)
            assertEquals(defaults, LocalStore(isolated).composerDefaults().forModels(listOf(model)))
            original.saveComposerDefaults(defaults.copy(serviceTier = null))
            val fresh = LocalStore(isolated).composerDefaults()
            assertEquals("high", fresh.forModels(listOf(model)).reasoningEffort)
            assertEquals(null, fresh.forModels(listOf(model)).serviceTier)
            val limited = model.copy(supportedReasoningEfforts = listOf(ReasoningEffortOption("low")), serviceTiers = emptyList())
            assertEquals("low", fresh.forModels(listOf(limited)).reasoningEffort)
            assertEquals(null, fresh.forModels(listOf(limited)).serviceTier)
        } finally {
            target.deleteSharedPreferences("$namespace-composer-defaults")
        }
    }
}
