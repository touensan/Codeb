package com.codex.remote.data.api

import org.junit.Assert.*
import org.junit.Test

class SkillMarketTest {
    private val content = "---\nname: codeb-check\ndescription: Check a Skill.\n---\n# Check\n"
    private val item = MarketSkill("codeb-check", "Check", "Check", "Notes", "1.0.0", "None", "https://example.com/source", "MIT", content, skillDigest(content))
    @Test fun verifiesPayloadBeforeInstallation() {
        assertEquals(item, item.validate())
        listOf(item.copy(id = "../escape"), item.copy(content = content + "tampered"), item.copy(sourceUrl = "http://example.com"),
            item.copy(sourceUrl = "https://user:password@example.com"), item.copy(version = "1; command"),
            item.copy(content = "name: missing-frontmatter", sha256 = skillDigest("name: missing-frontmatter"))).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
        assertTrue(runCatching { SkillCatalog(items = listOf(item, item)).validate() }.isFailure)
        assertTrue(runCatching { SkillCatalog(items = listOf(item.copy(published = false))).validate() }.isFailure)
        assertTrue(runCatching { SkillCatalog(schemaVersion = 2).validate() }.isFailure)
    }
}
