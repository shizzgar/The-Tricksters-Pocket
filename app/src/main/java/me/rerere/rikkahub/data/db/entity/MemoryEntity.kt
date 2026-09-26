package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index(value = ["assistant_id"])])
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("source_conversation_id", defaultValue = "NULL")
    val sourceConversationId: String? = null,
    @ColumnInfo("source_message_id", defaultValue = "NULL")
    val sourceMessageId: String? = null,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0,
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Int = 0,
    @ColumnInfo("history", defaultValue = "'[]'")
    val history: String = "[]",
    @ColumnInfo("deleted", defaultValue = "0")
    val deleted: Boolean = false,
)
