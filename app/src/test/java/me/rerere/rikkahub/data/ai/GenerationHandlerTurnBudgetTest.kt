package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the per-turn wall-clock budget is now read from [ToolRuntimeLimits] rather
 * than a deleted compile-time constant, and that the default value is 60 minutes.
 *
 * Full GenerationHandler instantiation requires Android Context + full Koin graph — beyond JVM
 * unit test scope. Instead, we test the invariants that GenerationHandler.kt relies on:
 *  - The runtime holder field exists and is readable/writable.
 *  - The long-context inference profile defaults to 60 min.
 *  - Writes to the holder are immediately visible (the deleted const had only one value;
 *    the holder can change dynamically).
 */
class GenerationHandlerTurnBudgetTest {

    private var prevBudget: Long = 0L

    @Before
    fun save() { prevBudget = ToolRuntimeLimits.turnBudgetMs }

    @After
    fun restore() { ToolRuntimeLimits.turnBudgetMs = prevBudget }

    @Test
    fun defaultBudget_is60Minutes() {
        // Long-context local inference needs room for cold prefill and queued requests.
        assertEquals(60L * 60L * 1_000L, TermuxDefaults.DEFAULT_TURN_BUDGET_MS)
    }

    @Test
    fun toolRuntimeLimits_initialDefault_matches60Minutes() {
        // On a fresh JVM process the holder initialises from TermuxDefaults.DEFAULT_TURN_BUDGET_MS.
        // We read prevBudget (captured before any test runs) to check what the holder started at.
        assertEquals(TermuxDefaults.DEFAULT_TURN_BUDGET_MS, prevBudget)
    }

    @Test
    fun turnBudget_canBeChangedAtRuntime() {
        val newBudget = 5L * 60_000L
        ToolRuntimeLimits.turnBudgetMs = newBudget
        assertEquals(newBudget, ToolRuntimeLimits.turnBudgetMs)
        assertNotEquals(prevBudget, ToolRuntimeLimits.turnBudgetMs)
    }

    @Test
    fun turnBudget_clampedValueFitsInHolder() {
        // Verify that a clamped value from TermuxDefaults survives a round-trip through
        // the holder without loss (no cast / truncation hazard).
        val clamped = TermuxDefaults.clampTurnBudgetMs(3_700_000L) // over max → should snap
        ToolRuntimeLimits.turnBudgetMs = clamped
        assertEquals(clamped, ToolRuntimeLimits.turnBudgetMs)
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS, ToolRuntimeLimits.turnBudgetMs)
    }
}
