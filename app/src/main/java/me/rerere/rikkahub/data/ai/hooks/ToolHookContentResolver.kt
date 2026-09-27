package me.rerere.rikkahub.data.ai.hooks

import java.util.concurrent.CancellationException
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_PROMPT_CHARS
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookActionType

sealed interface HookContentResolution {
    data class Resolved(val text: String, val source: String? = null) : HookContentResolution
    data class Error(
        val reason: String,
        val code: HookContentError = HookContentError.READ_FAILED,
    ) : HookContentResolution
}

enum class HookContentError {
    EMPTY_CONTENT,
    CONTENT_TOO_LARGE,
    INVALID_TEXT,
    INVALID_SKILL,
    SKILL_NOT_INSTALLED,
    INVALID_PATH,
    UNSUPPORTED_FILE_TYPE,
    FILE_NOT_FOUND,
    READ_FAILED,
    SECTION_NOT_FOUND,
    AMBIGUOUS_SECTION,
}

/**
 * Resolves only explicitly selected, already installed instructions. This never executes a skill,
 * follows a URL, or installs anything. Call on an IO dispatcher when using [SkillManager].
 *
 * The injected readers keep the parsing and validation testable without an Android filesystem.
 * SkillManager additionally canonicalizes paths, preventing symlinks from escaping the package.
 */
class ToolHookContentResolver(
    private val installedSkills: () -> Set<String>,
    private val readSkillFile: (skillName: String, relativePath: String) -> String?,
) {
    constructor(skillManager: SkillManager) : this(
        installedSkills = { skillManager.listSkills().map { it.name }.toSet() },
        readSkillFile = skillManager::readSkillFileCached,
    )

    fun resolve(action: ToolHookAction): HookContentResolution {
        return when (action.type) {
            ToolHookActionType.INLINE_PROMPT -> validateContent(action.prompt, source = null)
            ToolHookActionType.SKILL -> resolveSkill(action)
        }
    }

    private fun resolveSkill(action: ToolHookAction): HookContentResolution {
        val name = action.skillName
        if (name.isBlank() || name != name.trim() || name in setOf(".", "..") ||
            name.any { it == '/' || it == '\\' || it.isISOControl() }
        ) {
            return error(HookContentError.INVALID_SKILL, "Select a valid installed skill.")
        }
        val path = action.skillPath
        if (!isSafeRelativePath(path)) {
            return error(HookContentError.INVALID_PATH, "The skill file must use a relative path inside its package, without . or .. segments.")
        }
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in TEXT_EXTENSIONS) {
            return error(HookContentError.UNSUPPORTED_FILE_TYPE, "Hook instructions must be a Markdown or plain text file (.md, .markdown, .txt, .text).")
        }
        val section = action.skillSection.trim()
        if (section.any(Char::isISOControl)) {
            return error(HookContentError.SECTION_NOT_FOUND, "Enter a single Markdown heading to select a section.")
        }
        val raw = try {
            if (name !in installedSkills()) {
                return error(HookContentError.SKILL_NOT_INSTALLED, "The selected skill is no longer installed.")
            }
            readSkillFile(name, path)
                ?: return error(HookContentError.FILE_NOT_FOUND, "The selected file is unavailable in this skill.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SkillManager.SkillFileTooLargeException) {
            return error(HookContentError.CONTENT_TOO_LARGE, "The selected skill file exceeds the supported file size. Select a smaller instruction file.")
        } catch (_: Exception) {
            return error(HookContentError.READ_FAILED, "The selected skill instructions could not be read.")
        }
        if (!isInstructionText(raw)) {
            return error(HookContentError.INVALID_TEXT, "The selected file contains binary or unsupported control characters.")
        }
        // Strip metadata, not executable content: even fenced examples stay literal instructions.
        val markdown = extension == "md" || extension == "markdown"
        val body = if (markdown) SkillFrontmatterParser.extractBody(raw.removePrefix("\uFEFF")) else raw
        val source = "$name/$path" + if (section.isNotEmpty()) "#$section" else ""
        if (section.isEmpty()) return validateContent(body, source)
        if (!markdown) {
            return error(HookContentError.SECTION_NOT_FOUND, "Heading selection requires a Markdown file. Clear the section to use this whole text file.")
        }
        return when (val selected = selectSection(body, section)) {
            is SectionResult.Found -> validateContent(selected.text, source)
            SectionResult.Missing -> error(HookContentError.SECTION_NOT_FOUND, "The selected Markdown heading was not found in the skill file.")
            SectionResult.Ambiguous -> error(HookContentError.AMBIGUOUS_SECTION, "Several Markdown headings have this name. Use a uniquely named heading or a separate instruction file.")
        }
    }

    private fun validateContent(raw: String, source: String?): HookContentResolution {
        val text = raw.trim()
        if (text.isEmpty()) return error(HookContentError.EMPTY_CONTENT, "Hook instructions are empty.")
        if (text.length > TOOL_HOOK_MAX_PROMPT_CHARS) {
            return error(HookContentError.CONTENT_TOO_LARGE, "Hook instructions exceed $TOOL_HOOK_MAX_PROMPT_CHARS characters. Shorten the prompt or select a smaller skill section.")
        }
        if (!isInstructionText(text)) {
            return error(HookContentError.INVALID_TEXT, "Hook instructions contain binary or unsupported control characters.")
        }
        return HookContentResolution.Resolved(text, source)
    }

    private sealed interface SectionResult {
        data class Found(val text: String) : SectionResult
        data object Missing : SectionResult
        data object Ambiguous : SectionResult
    }

    private data class Heading(val line: Int, val level: Int, val title: String)

    /** Keeps the selected heading and descendants, stopping at the next peer or ancestor heading. */
    private fun selectSection(markdown: String, section: String): SectionResult {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val headings = mutableListOf<Heading>()
        var fenceCharacter: Char? = null
        var fenceLength = 0
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fence = FENCE.find(line)
            if (fenceCharacter != null) {
                if (fence != null && fence.groupValues[1].first() == fenceCharacter &&
                    fence.groupValues[1].length >= fenceLength && fence.groupValues[2].isBlank()
                ) {
                    fenceCharacter = null
                }
                index++
                continue
            }
            if (fence != null && (fence.groupValues[1].first() != '`' || '`' !in fence.groupValues[2])) {
                fenceCharacter = fence.groupValues[1].first()
                fenceLength = fence.groupValues[1].length
                index++
                continue
            }
            val atx = ATX_HEADING.find(line)
            if (atx != null) {
                val title = CLOSING_HASHES.replace(atx.groupValues[2], "").trim()
                headings += Heading(index, atx.groupValues[1].length, title)
            } else if (index + 1 < lines.size && isSetextTitle(line)) {
                val underline = SETEXT_UNDERLINE.find(lines[index + 1])
                if (underline != null) {
                    headings += Heading(index, if (underline.groupValues[1].first() == '=') 1 else 2, line.trim())
                    index += 2
                    continue
                }
            }
            index++
        }
        val matches = headings.filter { it.title.equals(section, ignoreCase = true) }
        if (matches.isEmpty()) return SectionResult.Missing
        if (matches.size != 1) return SectionResult.Ambiguous
        val selected = matches.single()
        val end = headings.firstOrNull { it.line > selected.line && it.level <= selected.level }?.line ?: lines.size
        return SectionResult.Found(lines.subList(selected.line, end).joinToString("\n"))
    }

    private fun isSetextTitle(line: String): Boolean {
        val text = line.trimStart()
        return line.isNotBlank() && !line.startsWith("    ") && !line.startsWith('\t') &&
            !text.startsWith('>') && !text.startsWith('#') && !text.startsWith("- ") &&
            !text.startsWith("* ") && !text.startsWith("+ ") && !ORDERED_LIST.containsMatchIn(text)
    }

    private fun error(code: HookContentError, reason: String) = HookContentResolution.Error(reason, code)

    companion object {
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "text")
        private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
        private val ATX_HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|[ \\t]*)$")
        private val CLOSING_HASHES = Regex("[ \\t]+#+[ \\t]*$")
        private val SETEXT_UNDERLINE = Regex("^ {0,3}(=+|-+)[ \\t]*$")
        private val ORDERED_LIST = Regex("^[0-9]+[.)] ")

        private fun isSafeRelativePath(path: String): Boolean = path.isNotBlank() &&
            path == path.trim() && !path.startsWith('/') && !path.startsWith('~') &&
            path.none { it == '\\' || it == ':' || it.isISOControl() } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        private fun isInstructionText(text: String): Boolean =
            text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
    }
}
