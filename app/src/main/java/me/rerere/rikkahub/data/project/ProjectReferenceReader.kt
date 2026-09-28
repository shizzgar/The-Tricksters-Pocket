package me.rerere.rikkahub.data.project

import me.rerere.rikkahub.data.repository.ProjectReferenceFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/** Read only explicitly linked app-managed references, independent of shell backend. */
class ProjectReferenceReader(private val filesDir: File) {
    data class Page(val content: String, val nextCursor: Long, val hasMore: Boolean, val encoding: String)

    fun resolve(reference: ProjectReferenceFile): File {
        require(!File(reference.relativePath).isAbsolute) { "Reference path must be relative" }
        val upload = File(filesDir, "upload").canonicalFile
        require(upload.parentFile == filesDir.canonicalFile && upload.name == "upload") { "Managed uploads directory cannot redirect outside app storage" }
        val candidate = File(filesDir, reference.relativePath).canonicalFile
        require(candidate.toPath().startsWith(upload.toPath()) && candidate != upload) { "Reference is outside managed uploads" }
        require(candidate.isFile) { "Reference file no longer exists" }
        return candidate
    }

    fun read(
        reference: ProjectReferenceFile,
        cursor: Long = 0,
        limit: Int = 12000,
        rawBytes: Boolean = false,
        parseDocument: ((File, String) -> String)? = null,
    ): Page {
        require(cursor >= 0) { "cursor must be nonnegative" }
        require(limit > 0) { "limit must be positive" }
        val file = resolve(reference)
        if (rawBytes) return RandomAccessFile(file, "r").use { input ->
            require(cursor <= input.length()) { "cursor is beyond the reference" }
            input.seek(cursor)
            val bytes = ByteArray(minOf(limit, 8192, (input.length() - cursor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            input.readFully(bytes)
            val next = cursor + bytes.size
            Page(Base64.getEncoder().encodeToString(bytes), next, next < input.length(), "base64")
        }
        require(file.length() <= MAX_TEXT_FILE_BYTES) { "Reference exceeds the text extraction limit; use mode=bytes for bounded pages" }
        val mime = reference.mimeType.substringBefore(';').lowercase()
        val content = if (mime in DOCUMENT_TYPES) {
            requireNotNull(parseDocument) { "Document extraction is unavailable; use mode=bytes" }(file, mime)
        } else {
            require(mime.startsWith("text/") || mime in TEXT_TYPES || file.extension.lowercase() in TEXT_EXTENSIONS) {
                "This format has no text extractor; use mode=bytes to read the original reference"
            }
            // Bound before decoding even if a linked file grows after the length check.
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAX_TEXT_FILE_BYTES) { "Reference exceeds the text extraction limit; use mode=bytes" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
        require(cursor <= content.length) { "cursor is beyond the extracted text" }
        val start = cursor.toInt()
        var end = minOf(content.length, start + minOf(limit, 16000))
        // Avoid splitting a supplementary character between two pages.
        if (end < content.length && end > start && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end++
        return Page(content.substring(start, end), end.toLong(), end < content.length, "text")
    }

    companion object {
        const val MAX_TEXT_FILE_BYTES = 8L * 1024 * 1024
        private val DOCUMENT_TYPES = setOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/epub+zip")
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "json", "jsonl", "xml", "csv", "tsv", "yaml", "yml", "kt", "java", "py", "js", "ts", "sh", "c", "cpp", "h", "html", "css", "log", "toml", "ini", "properties")
    }
}
