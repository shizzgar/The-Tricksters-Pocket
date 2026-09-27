package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** A hook contains only a user-authored instruction snapshot, never trusted tool output. */
@Serializable
data class ToolHookNotice(
    val id: String,
    val ruleId: String,
    val name: String,
    val reason: String,
    val content: String,
    val source: String? = null,
    val status: ToolHookNoticeStatus = ToolHookNoticeStatus.PENDING,
    /** Dispatch records transport handoff; it does not claim the model obeyed the instruction. */
    val requestId: String? = null,
)

@Serializable
enum class ToolHookNoticeStatus { PENDING, DISPATCHED, SKIPPED }
