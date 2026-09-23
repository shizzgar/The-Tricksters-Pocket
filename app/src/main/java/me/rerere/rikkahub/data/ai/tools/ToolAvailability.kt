package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Assistant

internal fun availableLocalOptions(
    options: List<LocalToolOption>,
    enabledSkills: Set<String>,
    installedSkills: Set<String>,
): List<LocalToolOption> = options.filter { option ->
    when (option) {
        LocalToolOption.Whisper -> LocalToolOption.Termux in options
        LocalToolOption.SkillImport, LocalToolOption.SkillManagement, LocalToolOption.JsSkills ->
            enabledSkills.any { it in installedSkills }
        else -> true
    }
}

internal fun filterLocalTools(tools: List<Tool>, disabled: Set<String>): List<Tool> {
    // use_skill owns the skill prompt. Turning it off also removes dependent skill tools.
    return tools.filter { it.name !in disabled && ("use_skill" !in disabled || !isSkillTool(it.name)) }
}

internal fun isSkillTool(name: String): Boolean = name == "use_skill" || name == "run_js" ||
    name == "termux_skill_sync" || name.startsWith("skill_")

/** Defaults belong to newly-created profiles; an explicitly empty saved selection stays empty. */
internal fun seedDefaultAssistantSkills(assistant: Assistant, newSkills: Set<String>): Assistant =
    if (assistant.enabledSkills.isEmpty()) assistant else assistant.copy(enabledSkills = assistant.enabledSkills + newSkills)
