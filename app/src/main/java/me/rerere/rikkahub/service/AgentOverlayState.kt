package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.provider.GenerationPhase
import me.rerere.ai.provider.GenerationProgress
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot

internal enum class AgentOverlayPhase { PREPARING, QUEUED, WAITING, RECEIVING, TOOL, APPROVAL, CONTINUING }

internal data class AgentOverlaySession(
    val id: String,
    val assistantName: String,
    val phase: AgentOverlayPhase,
    val processingStatus: String?,
    val context: ContextUsageSnapshot,
)

internal data class AgentOverlayState(val session: AgentOverlaySession, val activeCount: Int)

/** Each ring represents one request's context. Parallel chats must never sum their budgets. */
internal fun selectAgentOverlayState(sessions: List<AgentOverlaySession>): AgentOverlayState? {
    val selected = sessions.sortedWith(
        compareByDescending<AgentOverlaySession> { session ->
            session.context.contextLimit?.let { session.context.usedTokens.toDouble() / it } ?: -1.0
        }
            .thenByDescending { it.context.usedTokens }
            .thenBy { it.id },
    ).firstOrNull() ?: return null
    return AgentOverlayState(selected, sessions.size)
}

/** Switching the active set cancels old collectors, so late updates cannot resurrect a pill. */
internal fun agentOverlayStates(sessions: Flow<List<Flow<AgentOverlaySession>>>): Flow<AgentOverlayState?> =
    sessions.flatMapLatest { active ->
        if (active.isEmpty()) flowOf(null)
        else combine(active) { selectAgentOverlayState(it.toList()) }
    }.distinctUntilChanged()

internal fun agentOverlayPhase(progress: GenerationProgress?, tools: List<UIMessagePart.Tool>): AgentOverlayPhase {
    if (tools.any { !it.isExecuted && it.executionStartedAt != null }) return AgentOverlayPhase.TOOL
    if (tools.any { it.isPending }) return AgentOverlayPhase.APPROVAL
    return when (progress?.phase) {
        null, GenerationPhase.PREPARING -> AgentOverlayPhase.PREPARING
        GenerationPhase.QUEUED -> AgentOverlayPhase.QUEUED
        GenerationPhase.WAITING -> AgentOverlayPhase.WAITING
        GenerationPhase.RECEIVING -> AgentOverlayPhase.RECEIVING
        // A finished model request is often followed by tools or another autonomous step.
        GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED -> AgentOverlayPhase.CONTINUING
    }
}
