package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*

internal enum class SkillOperationStatus { PENDING, RUNNING, APPROVAL, DENIED, COMPLETED, FAILED, PARTIAL }
internal data class SkillDocumentPreview(val path: String, val text: String, val language: String)
internal data class SkillPresentation(
    val name: String,
    val path: String?,
    val status: SkillOperationStatus,
    val documents: List<SkillDocumentPreview>,
    val metadata: JsonObject,
    val errors: List<String>,
    val binary: Boolean,
)

internal fun skillLanguage(path: String): String = when (val extension = path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown", "mdx" -> "markdown"
    "py" -> "python"
    "sh", "bash", "zsh" -> "bash"
    "js", "mjs", "cjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "kt", "kts" -> "kotlin"
    "yml" -> "yaml"
    "json", "yaml", "toml", "xml", "html", "css", "java", "sql", "rust", "go" -> extension
    else -> "text"
}

internal fun presentSkill(
    toolName: String, arguments: JsonElement, outputs: List<String>,
    loading: Boolean = false, started: Boolean = false, pending: Boolean = false, denied: Boolean = false,
): SkillPresentation {
    val metadata = linkedMapOf<String, JsonElement>()
    val documents = mutableListOf<SkillDocumentPreview>()
    val errors = mutableListOf<String>()
    var binary = false
    val requestedPath = arguments.getStringContent("path")
    val defaultPath = requestedPath ?: "SKILL.md"
    outputs.forEach { text ->
        val sync = text.startsWith("Termux skill package: ")
        val objectValue = runCatching { Json.parseToJsonElement(text.removePrefix("Termux skill package: ")) as? JsonObject }.getOrNull()
        val envelope = objectValue?.takeIf { obj -> sync || obj.keys.any { it in setOf("error", "ok", "success", "content_md", "revision", "skill_root", "entries") } }
        if (envelope == null) {
            if (text.isNotBlank()) documents += SkillDocumentPreview(defaultPath, text, skillLanguage(defaultPath))
        } else {
            metadata.putAll(envelope.filterKeys { it !in setOf("content", "content_md") })
            val error = envelope.getStringContent("error")
            if (error != null || envelope["ok"] == JsonPrimitive(false) || envelope["success"] == JsonPrimitive(false)) {
                errors += listOfNotNull(error, envelope.getStringContent("detail"), envelope.getStringContent("recovery")).joinToString("\n").ifBlank { "Operation failed" }
            }
            val encoded = envelope.getStringContent("encoding") == "base64"
            binary = binary || encoded
            if (!encoded) {
                val body = envelope.getStringContent("content_md") ?: envelope.getStringContent("content")
                if (body != null) {
                    val path = envelope.getStringContent("path") ?: defaultPath
                    documents += SkillDocumentPreview(path, body, if (envelope.containsKey("content_md")) "markdown" else skillLanguage(path))
                }
            }
        }
    }
    val status = when {
        denied -> SkillOperationStatus.DENIED
        pending -> SkillOperationStatus.APPROVAL
        errors.isNotEmpty() && documents.isNotEmpty() -> SkillOperationStatus.PARTIAL
        errors.isNotEmpty() -> SkillOperationStatus.FAILED
        outputs.isNotEmpty() -> SkillOperationStatus.COMPLETED
        loading && started -> SkillOperationStatus.RUNNING
        else -> SkillOperationStatus.PENDING
    }
    return SkillPresentation(arguments.getStringContent("name") ?: metadata["name"]?.jsonPrimitive?.contentOrNull ?: toolName,
        requestedPath, status, documents, JsonObject(metadata), errors, binary)
}
