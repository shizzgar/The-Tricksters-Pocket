package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
    val kind: String = "TEXT",
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * Schema for the message_fts FTS5 virtual table. Defined here so the table-init path in
 * DataSourceModule and the Doctor's "rebuild search index" repair path use the same DDL.
 * If the columns ever change, both the CREATE in DataSourceModule and the INSERT in
 * [MessageFtsManager.indexConversation] need updating in lock-step.
 */
const val MESSAGE_FTS_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
        text,
        node_id UNINDEXED,
        message_id UNINDEXED,
        conversation_id UNINDEXED,
        title UNINDEXED,
        update_at UNINDEXED,
        tokenize = 'simple'
    )
"""

class MessageFtsManager(private val database: AppDatabase, private val context: android.content.Context? = null) {

    private val db get() = database.openHelper.writableDatabase

    private fun ensureWorkSchema() {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_work_fts USING fts5(text, node_id UNINDEXED, message_id UNINDEXED, conversation_id UNINDEXED, title UNINDEXED, update_at UNINDEXED, kind UNINDEXED, tool_name UNINDEXED, tokenize = 'simple')")
        db.execSQL("CREATE TABLE IF NOT EXISTS work_search_version (version INTEGER NOT NULL)")
    }

    suspend fun isWorkIndexReady(): Boolean = withContext(Dispatchers.IO) {
        ensureWorkSchema()
        db.query("SELECT version FROM work_search_version LIMIT 1").use { it.moveToFirst() && it.getInt(0) == 1 }
    }

    suspend fun markWorkIndexReady() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM work_search_version")
        db.execSQL("INSERT INTO work_search_version(version) VALUES (1)")
    }


    /**
     * Drop and recreate the message_fts virtual table. Use this when SQLite reports
     * a malformed inverted index (PRAGMA integrity_check) — DELETE-from-FTS5 doesn't
     * free corrupted index pages, only DROP TABLE does. Safe because message_fts is a
     * standalone search projection; the actual content lives in `messages` and gets
     * reinserted by the caller (see [me.rerere.rikkahub.data.repository.ConversationRepository.rebuildAllIndexes]).
     */
    suspend fun dropAndRecreate() = withContext(Dispatchers.IO) {
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL(MESSAGE_FTS_CREATE_SQL.trimIndent())
        db.execSQL("DROP TABLE IF EXISTS message_work_fts")
        db.execSQL("DROP TABLE IF EXISTS work_search_version")
        ensureWorkSchema()
    }

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        ensureWorkSchema()
        db.execSQL("DELETE FROM message_work_fts WHERE conversation_id = ?", arrayOf(conversationId))
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                (extractWorkSearchText(message.parts) + localDocumentText(message.parts)).forEach { part ->
                    // Retain long tool output using separately ranked chunks, not a silent 10k cutoff.
                    part.text.chunked(16000).forEach { chunk ->
                        db.execSQL("INSERT INTO message_work_fts(text,node_id,message_id,conversation_id,title,update_at,kind,tool_name) VALUES (?,?,?,?,?,?,?,?)",
                            arrayOf(chunk, node.id.toString(), message.id.toString(), conversationId, conversation.title,
                                message.createdAt.toString().let { java.time.LocalDateTime.parse(it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli().toString() }, part.kind.name, part.toolName))
                    }
                }
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    private fun localDocumentText(parts: List<UIMessagePart>): List<WorkSearchText> {
        val root = context?.filesDir?.canonicalFile ?: return emptyList()
        return parts.flatMap { part ->
            if (part is UIMessagePart.Tool) localDocumentText(part.output)
            else if (part is UIMessagePart.Document && (part.mime.startsWith("text/") || part.mime in setOf("application/json", "application/xml", "application/javascript"))) {
                runCatching {
                    val file = java.io.File(java.net.URI(part.url)).canonicalFile
                    val allowed = listOf("upload", "tool_outputs").any { folder -> file.toPath().startsWith(java.io.File(root, folder).toPath()) }
                    if (allowed && file.isFile && file.length() <= 2 * 1024 * 1024) listOf(WorkSearchText(WorkSearchKind.FILE, part.fileName + "\n" + file.readText())) else emptyList()
                }.getOrDefault(emptyList())
            } else emptyList()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        ensureWorkSchema()
        db.execSQL("DELETE FROM message_work_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    /**
     * Narrow update of the denormalized `title` column for a rename, without touching the
     * indexed message rows. Use this instead of [indexConversation] when only the title
     * changed, so a title-only rename doesn't require deleting/reinserting every message.
     */
    suspend fun updateConversationTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        db.execSQL(
            "UPDATE message_fts SET title = ? WHERE conversation_id = ?",
            arrayOf(title, conversationId)
        )
        ensureWorkSchema()
        db.execSQL("UPDATE message_work_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
        ensureWorkSchema()
        db.execSQL("DELETE FROM message_work_fts")
        db.execSQL("DELETE FROM work_search_version")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
        filter: WorkSearchFilter = WorkSearchFilter(),
        limit: Int = 50,
        offset: Int = 0,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        ensureWorkSchema()
        val narrowed = workSearchSql(filter, assistantId)
        val args = mutableListOf<Any>(keyword).apply { addAll(narrowed.args); add(limit.coerceIn(1, 100)); add(offset.coerceAtLeast(0)) }
        val results = mutableListOf<MessageSearchResult>()
        db.query("""
            SELECT node_id,message_id,conversation_id,title,update_at,
                   simple_snippet(message_work_fts,0,'[',']','...',30),kind
            FROM message_work_fts
            WHERE text MATCH jieba_query(?) AND ${narrowed.where}
            ORDER BY ${sort.orderBy}, rowid
            LIMIT ? OFFSET ?
        """.trimIndent(), args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) results += MessageSearchResult(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),Instant.ofEpochMilli(cursor.getLong(4)),cursor.getString(5),cursor.getString(6))
        }
        results
    }

}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
