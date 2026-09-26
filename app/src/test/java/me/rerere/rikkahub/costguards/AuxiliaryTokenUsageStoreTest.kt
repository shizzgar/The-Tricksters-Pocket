package me.rerere.rikkahub.costguards

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AuxiliaryTokenUsageStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `auxiliary usage survives restart and duplicate acknowledgement does not double count`() {
        val root = temp.newFolder()
        val id = UUID.randomUUID().toString()
        AuxiliaryTokenUsageStore.initialize(root)
        val usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120)
        AuxiliaryTokenUsageStore.record(id, "compaction-1", usage)
        AuxiliaryTokenUsageStore.record(id, "compaction-1", usage)
        AuxiliaryTokenUsageStore.record(id, "title-without-usage", null)
        AuxiliaryTokenUsageStore.initialize(temp.newFolder())
        AuxiliaryTokenUsageStore.initialize(root)
        val totals = AuxiliaryTokenUsageStore.totals(id)
        assertEquals(120L, totals.totalTokens)
        assertEquals(1, totals.messageCount)
        assertEquals(1, totals.unmeasuredMessages)
        AuxiliaryTokenUsageStore.delete(id)
        AuxiliaryTokenUsageStore.record(id, "late-completion", usage)
        assertEquals(0L, AuxiliaryTokenUsageStore.totals(id).totalTokens)
    }
}
