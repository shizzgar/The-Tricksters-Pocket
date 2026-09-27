package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookActionType
import me.rerere.rikkahub.data.model.ToolHookScope
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ToolHookEditorValidationTest {
    private val valid = ToolHook(name = "JADX repair", scope = ToolHookScope(conversationIds = setOf(Uuid.random())), action = ToolHookAction(prompt = "Read the installed help before correcting the command."))

    @Test fun blankDraftCannotBecomeAnActiveUnscopedRule() {
        val errors = toolHookEditorErrors(ToolHook())
        assertTrue(R.string.hooks_name_required in errors)
        assertTrue(R.string.hooks_scope_required in errors)
        assertTrue(R.string.hooks_prompt_required in errors)
        assertTrue(toolHookEditorErrors(valid).isEmpty())
    }

    @Test fun repeatAndContextLimitsAreCheckedEvenForDisabledRules() {
        assertTrue(toolHookEditorErrors(valid.copy(enabled = false, maxFiringsPerTurn = 0)).contains(R.string.hooks_cap_invalid))
        assertTrue(toolHookEditorErrors(valid.copy(maxFiringsPerTurn = 11)).contains(R.string.hooks_cap_invalid))
        assertTrue(toolHookEditorErrors(valid.copy(action = valid.action.copy(prompt = "x".repeat(16_001)))).contains(R.string.hooks_prompt_too_long))
        assertTrue(toolHookEditorErrors(valid.copy(maxFiringsPerTurn = 10, action = valid.action.copy(prompt = "x".repeat(16_000)))).isEmpty())
    }

    @Test fun skillReferenceRejectsTraversalAndExecutableFilesButAllowsTextInstructions() {
        val skill = valid.copy(action = ToolHookAction(type = ToolHookActionType.SKILL, skillName = "jadx-guide"))
        listOf("../SKILL.md", "/tmp/SKILL.md", "a/../SKILL.md", "scripts/run.sh", "a\\b.md", "~/guide.md", "https://host/guide.md", "\nnotes.md").forEach { path ->
            assertTrue(path, R.string.hooks_path_invalid in toolHookEditorErrors(skill.copy(action = skill.action.copy(skillPath = path))))
        }
        listOf("SKILL.md", "references/jadx.markdown", "notes.txt", "guide.text").forEach { path ->
            assertTrue(path, toolHookEditorErrors(skill.copy(action = skill.action.copy(skillPath = path))).isEmpty())
        }
    }

    @Test fun commandConditionsCannotExceedRuntimeLimits() {
        assertTrue(R.string.hooks_match_too_long in toolHookEditorErrors(valid.copy(condition = valid.condition.copy(stderrContains = "x".repeat(1025)))))
        assertTrue(R.string.hooks_tools_invalid in toolHookEditorErrors(valid.copy(condition = valid.condition.copy(toolNames = (0..32).map { "tool$it" }.toSet()))))
        assertTrue(toolHookEditorErrors(valid.copy(condition = valid.condition.copy(stderrContains = "x".repeat(1024)))).isEmpty())
    }

    @Test fun switchingActionIgnoresInactivePromptAndRequiresChosenSkill() {
        val rule = valid.copy(action = ToolHookAction(type = ToolHookActionType.SKILL, prompt = "x".repeat(16_001)))
        val errors = toolHookEditorErrors(rule)
        assertTrue(R.string.hooks_skill_required in errors)
        assertFalse(R.string.hooks_prompt_too_long in errors)
        assertTrue(toolHookEditorErrors(rule.copy(action = rule.action.copy(skillName = "guide"))).isEmpty())
    }
}
