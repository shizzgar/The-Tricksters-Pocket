package me.rerere.rikkahub.service

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ContextUsageCalculator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant

/** Observes real lifecycle/progress events, including background and child sessions. No polling. */
internal class AgentOverlayMonitor(
    private val context: Application,
    private val settings: Flow<Settings>,
    private val conversationRepo: ConversationRepository,
) {
    fun start(scope: CoroutineScope, sessions: Flow<List<ConversationSession>>) = scope.launch(Dispatchers.Default) {
        try {
            val observations = agentOverlayStates(sessions.map { active ->
                active.map { session ->
                    combine(session.state, session.generationProgress.state, session.processingStatus,
                        conversationRepo.observeCompaction(session.id), settings) { chat, progress, status, compaction, current ->
                        val assistant = current.getAssistantById(chat.assistantId) ?: current.getCurrentAssistant()
                        val model = current.findModelById(chat.chatModelId ?: assistant.chatModelId ?: current.chatModelId)
                        val startedAt = progress?.let {
                            Instant.ofEpochMilli(System.currentTimeMillis() -
                                ((System.nanoTime() / 1_000_000) - it.startedAt).coerceAtLeast(0))
                        }
                        AgentOverlaySession(
                            id = session.id.toString(), assistantName = assistant.name,
                            phase = agentOverlayPhase(progress, chat.currentMessages.lastOrNull()?.parts
                                ?.filterIsInstance<UIMessagePart.Tool>().orEmpty()),
                            processingStatus = status,
                            context = ContextUsageCalculator.snapshot(chat, assistant, current, model, compaction,
                                streaming = true, liveUsage = progress?.usage.takeIf {
                                    chat.currentMessages.lastOrNull()?.modelId == model?.id
                                }, liveRequestStartedAt = startedAt),
                        )
                    }
                }
            })
            // Custom palettes can be expensive to generate; never rebuild one per stream chunk.
            val palette = combine(settings, themeChanges()) { current, _ -> agentOverlayPalette(context, current) }
                .distinctUntilChanged()
            combine(observations, palette) { state, colors ->
                state to colors
            }.distinctUntilChanged().conflate().collect { (state, palette) ->
                AgentOverlay.update(context, state, palette)
            }
        } finally {
            AgentOverlay.hide(context)
        }
    }

    /** Theme preference changes and system night/wallpaper configuration updates are events too. */
    private fun themeChanges() = callbackFlow {
        val prefs = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "colorMode" || key == "amoledDark") trySend(Unit)
        }
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) { trySend(Unit) }
            override fun onLowMemory() = Unit
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        context.registerComponentCallbacks(callbacks)
        trySend(Unit)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            context.unregisterComponentCallbacks(callbacks)
        }
    }
}
