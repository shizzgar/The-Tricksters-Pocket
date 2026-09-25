package me.rerere.rikkahub.data.ai.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Correlates a dispatched child with the exact persisted parent tool event. */
class ExecutingToolCall(val id: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExecutingToolCall>
}
