package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    /**
     * Optional disk helper: supplies the mtime-aware read cache the per-turn auto-load
     * path uses, and backs the read-only `skill_get_content` tool. Everything needed for
     * correctness already lives on [SkillMetadata], so when this is absent the files are
     * read straight from the skill's own directory. Same result, just uncached, and the
     * content tool is not offered.
     */
    skillManager: SkillManager? = null,
    termuxBridge: me.rerere.rikkahub.skills.TermuxSkillBridge? = null,
    currentEnabledSkills: () -> Set<String> = { enabledSkills },
): List<Tool> {
    fun available() = (skillManager?.listSkills() ?: allSkills).filter { it.name in currentEnabledSkills() }
    if (available().isEmpty()) return emptyList()

    return listOfNotNull(
        termuxBridge?.let { bridge ->
            Tool(
                name = "termux_skill_sync",
                description = "Make an enabled skill's COMPLETE package available in Termux, including SKILL.md, scripts, references and binary assets. Returns skill_root: use it as working_dir with Termux tools. Reuses verified unchanged revisions. Copies files only; does not execute scripts or install dependencies. Use after auto-loaded skills or when automatic sync is disabled.",
                parameters = { InputSchema.Obj(properties = buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Enabled skill name") })
                }, required = listOf("name")) },
                execute = { args ->
                    val name = (args.jsonObject["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val skill = available().firstOrNull { it.name == name }
                    val result = if (skill == null) buildJsonObject { put("success", false); put("error", "skill_not_enabled_or_missing") }
                        else bridge.prepare(skill)
                    listOf(UIMessagePart.Text(result.toString()))
                },
            )
        },
        // Phase 16 audit fix — read-only accessor so the LLM can show a skill's content
        // without re-installing it. Sits under the same skills surface as use_skill.
        skillManager?.let { manager ->
            skillGetContentTool(
                enabledSkills = enabledSkills,
                allSkills = allSkills,
                contentReader = manager::getContent,
            )
        },
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    if (available().isEmpty()) return@buildString
                    appendLine("Skill examples are instructions, not tool permissions. Only the tools declared in this request are available. Never call a disabled tool mentioned by a skill.")
                    if (termuxBridge != null) {
                        appendLine("Enabled skills can be copied as full packages to Termux using termux_skill_sync. use_skill also prepares them when automatic sync is enabled. Only a successful result's skill_root is a usable Termux path; The Trickster's Pocket private paths are not accessible to Termux. Auto-loaded instructions do not themselves sync files: call termux_skill_sync before running their scripts. Run from skill_root so relative paths resolve; keep generated files in a separate workspace. Copies never grant extra tool permissions.")
                    }
                    // Auto-load skills with `auto_load: true` in their SKILL.md frontmatter:
                    // their body (auto_load_path file if set, else SKILL.md) is inlined into
                    // the system prompt every turn, no `use_skill` call needed. Use for the
                    // "core persona" skills (agent-core/SOUL.md). Models that previously
                    // never bothered to discover the SOUL via use_skill now see it on turn 1.
                    val autoLoaded = available().filter { it.autoLoad }
                    autoLoaded.forEach { skill ->
                        val path = skill.autoLoadPath
                        // Both branches go through SkillManager's mtime-aware cache so
                        // the per-turn auto-load reads are O(stat) on cache hit rather
                        // than O(file I/O) — N auto-load skills × every turn used to
                        // re-read SOUL/HEARTBEAT/etc from disk every time.
                        val body = runCatching {
                            if (path.isNullOrBlank()) {
                                skillManager?.readSkillBody(skill.name)
                                    ?: SkillFrontmatterParser
                                        .extractBody(skill.skillFile.readText())
                            } else {
                                skillManager?.readSkillFileCached(skill.name, path)
                                    ?: SkillPaths.resolveSkillFile(skill.skillDir, path)
                                        ?.takeIf { file -> file.exists() }
                                        ?.readText()
                            }
                        }.getOrNull()
                        if (!body.isNullOrBlank()) {
                            appendLine(body.trim())
                            appendLine()
                        }
                    }

                    // Lazy skills — listed for discovery; loaded on demand via `use_skill`.
                    val lazy = available().filterNot { it.autoLoad }
                    if (lazy.isNotEmpty()) {
                        appendLine("**Skills**")
                        appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                        appendLine("<available_skills>")
                        lazy.forEach { skill ->
                            appendLine("  <skill>")
                            appendLine("    <name>${skill.name}</name>")
                            appendLine("    <description>${skill.description}</description>")
                            appendLine("  </skill>")
                        }
                        append("</available_skills>")
                        appendLine()
                    }
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                // Return structured error envelopes instead of throwing. AICore /
                // small models hit `use_skill` with `{}` (no name) regularly; before
                // this fix the LLM saw a 20-frame Java stack trace and gave up. The
                // recovery hint + available_skills list lets the model self-correct
                // on its next call.
                fun err(code: String, detail: String): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", code)
                            put("detail", detail)
                            put("recovery", "Re-call use_skill with one of the listed skill names in `name`.")
                            put(
                                "available_skills",
                                kotlinx.serialization.json.buildJsonArray {
                                    currentEnabledSkills().forEach {
                                        add(kotlinx.serialization.json.JsonPrimitive(it))
                                    }
                                },
                            )
                        }.toString()
                    )
                )
                // Refuse oversized skill files before reading them whole. SkillManager
                // enforces the same cap on its cached reads (readCached); this is the
                // model-facing envelope so the LLM gets a clean error instead of a
                // failed/empty read.
                fun tooLargeErr(file: java.io.File): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "skill_file_too_large")
                            put("max_bytes", SkillManager.MAX_SKILL_FILE_BYTES)
                            put("size_bytes", file.length())
                        }.toString()
                    )
                )
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: return@Tool err(
                        "missing_required_arg",
                        "use_skill requires a 'name' argument identifying which skill to load.",
                    )
                if (name !in currentEnabledSkills()) {
                    return@Tool err(
                        "skill_not_enabled",
                        "Skill '$name' is not in the enabled-skills set for this assistant.",
                    )
                }
                // Resolve through the skill's own directory rather than looking it up by
                // name. A skill whose frontmatter `name:` differs from its folder name
                // (e.g. folder "directory-name", name "Display Name") is unreachable by a
                // name-keyed lookup, which is the upstream bug this adopts the fix for.
                val skill = available().firstOrNull { skill -> skill.name == name }
                    ?: return@Tool err(
                        "skill_not_found",
                        "Skill '$name' is enabled but has no metadata entry on disk.",
                    )
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                if (path.isNullOrBlank()) {
                    val skillMd = skill.skillFile
                    if (!skillMd.exists()) {
                        return@Tool err(
                            "skill_body_not_found",
                            "Skill '$name' is enabled but its SKILL.md body could not be read on disk.",
                        )
                    }
                    if (skillMd.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                        return@Tool tooLargeErr(skillMd)
                    }
                    val content = SkillFrontmatterParser.extractBody(skillMd.readText())
                    val prepared = termuxBridge?.prepare(skill, automatic = true)
                    return@Tool listOfNotNull(UIMessagePart.Text(content), prepared?.let { result ->
                        UIMessagePart.Text("Termux skill package: " + result.toString())
                    })
                }
                val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                    ?: return@Tool err(
                        "path_outside_skill",
                        "Path '$path' resolves outside the '$name' skill directory.",
                    )
                if (!target.exists()) {
                    return@Tool err(
                        "skill_file_not_found",
                        "File '$path' does not exist in skill '$name'. Use only paths from Markdown links inside SKILL.md.",
                    )
                }
                if (target.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                    return@Tool tooLargeErr(target)
                }
                listOf(UIMessagePart.Text(target.readText()))
            }
        )
    )
}
