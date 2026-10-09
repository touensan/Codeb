package com.codex.remote.data.api

import org.junit.Assert.*
import org.junit.Test

class CloudConfigTest {
    @Test fun rejectsUnsafeLinksAndOnlyUpdatesTheMatchingPackage() {
        assertFalse(safeCloudLink("javascript:alert(1)"))
        assertFalse(safeCloudLink("https://user:password@example.com/file.apk"))
        assertFalse(safeCloudLink("http://example.com/file.apk"))
        val update = CloudUpdate(true, "com.codex.remote.dev", 5, "0.2.1", url = "https://codeb.baiyun.si/downloads/test.apk")
        assertTrue(update.available("com.codex.remote.dev", 4))
        assertFalse(update.available("com.codex.remote", 4))
        assertFalse(update.available("com.codex.remote.dev", 5))
        assertTrue(runCatching { CloudConfig(recommendations = listOf(CloudRecommendation("test", "test", url = "javascript:alert(1)"))).validate() }.isFailure)
        assertTrue(validEffortId("vendor-deep.v2"))
        assertFalse(validEffortId("很高"))
        assertFalse(validEffortId("deep\nmode"))
    }
}
