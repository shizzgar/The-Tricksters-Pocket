package me.rerere.rikkahub.service

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.MessageQueuePanel
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class LiveSteeringInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(value: String) = listOf(UIMessagePart.Text(value))
    private fun call(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = id, input = "{}")

    private class FixtureProvider : Provider<ProviderSetting.OpenAI> {
        val requests = CopyOnWriteArrayList<List<UIMessage>>()
        var response: suspend () -> List<UIMessagePart> = { listOf(UIMessagePart.Text("done")) }
        var stream: suspend FlowCollector<StreamChunk>.() -> Unit = { error("Unexpected stream") }
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()
        override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            requests += messages
            return TextGenerationResult("fixture", params.model.modelId, UIMessage(role = MessageRole.ASSISTANT, parts = response()), "stop")
        }
        override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            requests += messages
            stream()
        }
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> = error("Unexpected image request")
    }

    private data class Run(val messages: List<UIMessage>, val outcome: GenerationSliceOutcome, val boundary: String?, val reserved: List<QueuedMessage>)

    private suspend fun drive(
        provider: FixtureProvider,
        queue: MessageQueue,
        tools: List<Tool> = emptyList(),
        initial: List<UIMessage> = listOf(UIMessage(role = MessageRole.USER, parts = text("original task"))),
        afterTool: suspend (List<UIMessage>) -> List<UIMessage>? = { null },
    ): Run {
        val model = Model(modelId = "steering-fixture", abilities = listOf(ModelAbility.TOOL))
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val manager = ProviderManager(OkHttpClient(), context).apply { registerProvider("openai", provider) }
        val koin = GlobalContext.get()
        val loop = GenerationLoop(context, manager, Json, koin.get(), koin.get(), koin.get(), koin.get())
        var result = GenerationSliceOutcome(GenerationStopReason.FAILED)
        var history = initial
        var boundary: String? = null
        var reserved = emptyList<QueuedMessage>()
        withTimeout(15_000) {
            loop.generateText(settings = settings, model = model, messages = initial,
                assistant = Assistant(streamOutput = false, localTools = emptyList()), tools = tools,
                manageUiLifecycle = false, autonomousCycle = false, maxSteps = 5,
                awaitToolResultPersistence = true,
                claimSteeringInput = { at ->
                    reserved = queue.claimSteering()
                    if (reserved.isNotEmpty()) boundary = at
                    reserved.isNotEmpty()
                },
                onAfterToolExecution = afterTool, onStopped = { result = it },
            ).collect { chunk ->
                when (chunk) { is GenerationChunk.Messages -> chunk.applyAndAcknowledge { history = chunk.messages } }
            }
        }
        return Run(history, result, boundary, reserved)
    }

    @Test fun updateDuringModelResponsePreventsUnstartedToolCalls() = runBlocking {
        val queue = MessageQueue()
        var executed = false
        val provider = FixtureProvider().apply { response = {
            queue.enqueue(text("change the plan"), steerActiveTask = true)
            listOf(UIMessagePart.Reasoning(reasoning = "completed reasoning"), call("first"), call("second"))
        } }
        val result = drive(provider, queue, listOf(Tool(name = "first", description = "fixture", parameters = { InputSchema.Obj(buildJsonObject {}) }, execute = { executed = true; text("unexpected") })))
        assertFalse(executed)
        assertEquals(GenerationStopReason.USER_MESSAGE, result.outcome.reason)
        assertEquals("after_model_response", result.boundary)
        assertEquals(1, provider.requests.size)
        assertEquals(2, result.messages.last().getTools().size)
        assertTrue(result.messages.last().getTools().all { it.isExecuted && it.executionStartedAt == null })
        assertEquals("completed reasoning", result.messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
    }

    @Test fun updateDuringToolFinishesItAndSkipsRemainderOfBatch() = runBlocking {
        val queue = MessageQueue()
        val executed = mutableListOf<String>()
        val provider = FixtureProvider().apply { response = { listOf(call("first"), call("second")) } }
        val tools = listOf("first", "second").map { name -> Tool(name = name, description = "fixture", parameters = { InputSchema.Obj(buildJsonObject {}) }, execute = {
            assertEquals(name, currentCoroutineContext()[me.rerere.rikkahub.data.ai.tools.ExecutingToolCall]?.id)
            executed += name
            queue.enqueue(text("use a different destination"), steerActiveTask = true)
            yield()
            text("completed $name")
        }) }
        val result = drive(provider, queue, tools)
        assertEquals(listOf("first"), executed)
        assertEquals("after_tool", result.boundary)
        assertEquals(GenerationStopReason.USER_MESSAGE, result.outcome.reason)
        val calls = result.messages.last().getTools()
        assertEquals(text("completed first"), calls.first().output)
        assertNotNull(calls.first().executionStartedAt)
        assertNull(calls.last().executionStartedAt)
        assertTrue(calls.last().output.toString().contains("superseded_by_user_input"))
        assertEquals(1, provider.requests.size)
    }

    @Test fun compactionFinishesBeforeQueuedInputAndNoStaleRequestStarts() = runBlocking {
        val queue = MessageQueue()
        var compressed = false
        val provider = FixtureProvider().apply { response = { listOf(call("first")) } }
        val result = drive(provider, queue,
            tools = listOf(Tool(name = "first", description = "fixture", parameters = { InputSchema.Obj(buildJsonObject {}) }, execute = { text("completed") })),
            afterTool = { history ->
                assertTrue(history.last().getTools().single().isExecuted)
                queue.enqueue(text("new constraint"), steerActiveTask = true)
                assertTrue(queue.state.value.messages.none { it.isApplying })
                yield()
                compressed = true
                listOf(UIMessage(role = MessageRole.SYSTEM, parts = text("compacted summary"), isSynthetic = true)) + history.takeLast(1)
            })
        assertTrue(compressed)
        assertEquals("before_model_request", result.boundary)
        assertEquals(GenerationStopReason.USER_MESSAGE, result.outcome.reason)
        assertEquals(1, provider.requests.size)
        assertEquals("compacted summary", result.messages.first().toText())
    }

    @Test fun pendingApprovalStillBlocksQueuedInput() = runBlocking {
        val queue = MessageQueue().apply { enqueue(text("update"), steerActiveTask = true) }
        val provider = FixtureProvider()
        val pending = call("approval").copy(approvalState = ToolApprovalState.Pending)
        val result = drive(provider, queue, initial = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending))))
        assertEquals(GenerationStopReason.WAITING_APPROVAL, result.outcome.reason)
        assertTrue(result.reserved.isEmpty())
        assertFalse(queue.state.value.messages.single().isApplying)
        assertTrue(provider.requests.isEmpty())
    }

    private suspend fun withChatFixture(block: suspend (ChatService, FixtureProvider, Uuid, ConversationRepository) -> Unit) {
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val original = withTimeout(15_000) { store.settingsFlow.first { !it.init } }
        val manager = koin.get<ProviderManager>()
        val originalProvider = manager.getProviderByType(ProviderSetting.OpenAI())
        val provider = FixtureProvider()
        val model = Model(modelId = "steering-service-fixture")
        val assistant = Assistant(chatModelId = model.id, name = "Steering fixture", localTools = emptyList())
        val id = Uuid.random()
        val service = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        try {
            manager.registerProvider("openai", provider)
            store.update(original.copy(
                providers = original.providers + ProviderSetting.OpenAI(models = listOf(model)),
                assistants = original.assistants + assistant,
                enableSuggestion = false, enableAutoCompaction = false,
                networkSetting = original.networkSetting.copy(generationRuntime = original.networkSetting.generationRuntime.copy(autonomousContinuation = false)),
            ))
            withTimeout(15_000) { store.settingsFlow.first { settings -> settings.assistants.any { it.id == assistant.id } } }
            service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, title = "Steering fixture", messageNodes = emptyList()))
            service.addConversationReference(id)
            withTimeout(30_000) { block(service, provider, id, repo) }
        } finally {
            service.stopGeneration(id)
            service.getMessageQueueFlow(id).value.messages.forEach { service.removeQueuedMessage(id, it.id) }
            service.dropSession(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            manager.registerProvider("openai", originalProvider)
            store.update(original)
            withTimeout(15_000) { store.settingsFlow.first { settings -> settings.assistants.none { it.id == assistant.id } } }
        }
    }

    @Test fun chatServiceAppliesUpdatesWithinSameTaskWithAutonomyDisabled() = runBlocking {
        withChatFixture { service, provider, id, repo ->
            val responses = AtomicInteger()
            provider.stream = {
                val number = responses.incrementAndGet()
                if (number == 1) {
                    emit(StreamChunk.ReasoningStart("r"))
                    emit(StreamChunk.ReasoningDelta("r", "Current request keeps running"))
                    service.sendMessage(id, text("also use Russian"))
                    service.sendMessage(id, text("keep the original goal"))
                    assertEquals(2, service.getMessageQueueFlow(id).value.messages.size)
                    assertFalse(service.getConversationFlow(id).value.currentMessages.any { it.toText() == "also use Russian" })
                    emit(StreamChunk.ReasoningEnd("r"))
                } else {
                    assertEquals(2, number)
                    assertEquals(listOf("also use Russian", "keep the original goal"), provider.requests.last().filter { it.role == MessageRole.USER }.takeLast(2).map { it.toText() })
                }
                emit(StreamChunk.TextStart("text-$number"))
                emit(StreamChunk.TextDelta("text-$number", if (number == 1) "Current step finished" else "Updated task finished"))
                emit(StreamChunk.TextEnd("text-$number"))
                emit(StreamChunk.Finish("stop"))
            }
            service.sendMessage(id, text("original goal"))
            service.getGenerationJobStateFlow(id).first { it == null }
            assertEquals(2, responses.get())
            assertTrue(service.getMessageQueueFlow(id).value.messages.isEmpty())
            assertTrue(service.errors.value.filter { it.conversationId == id }.joinToString("\n") { it.error.stackTraceToString() },
                service.errors.value.none { it.conversationId == id })
            val saved = requireNotNull(repo.getConversationById(id)).currentMessages
            assertEquals(listOf("original goal", "also use Russian", "keep the original goal"), saved.filter { it.role == MessageRole.USER }.map { it.toText() })
            assertEquals(5, saved.size)
            assertEquals("Updated task finished", saved.last().toText())
            val trace = SessionJournal.at(context.filesDir).page(id.toString()).records
            assertEquals(1, trace.count { it.source == "task.started" })
            assertEquals(1, trace.count { it.source == "input.applied" })
            assertEquals("completed", service.agentTaskState(id)?.status)
        }
    }

    @Test fun openingActiveChildPreservesStreamAndBackgroundInitializationKeepsSelectedAssistant() = runBlocking {
        withChatFixture { service, provider, id, repo ->
            val store = GlobalContext.get().get<SettingsStore>()
            val selected = store.settingsFlow.value.assistantId
            val parent = Uuid.random()
            service.updateConversationState(id) { it.copy(parentConversationId = parent, subAgentRunId = id.toString()) }
            service.saveConversation(id, service.getConversationFlow(id).value)
            service.initializeConversation(id, selectAssistant = false)
            assertEquals(selected, store.settingsFlow.value.assistantId)
            provider.stream = {
                emit(StreamChunk.TextStart("text"))
                emit(StreamChunk.TextDelta("text", "Live child progress"))
                // Streaming can advance while DataStore yields. Check a live-only marker,
                // not equality of two snapshots that may legitimately contain different text.
                service.updateConversationState(id) { it.copy(parentToolCallId = "live-stream-marker") }
                service.initializeConversation(id)
                assertEquals("live-stream-marker", service.getConversationFlow(id).value.parentToolCallId)
                assertEquals(parent, service.getConversationFlow(id).value.parentConversationId)
                emit(StreamChunk.TextEnd("text"))
                emit(StreamChunk.Finish("stop"))
            }
            service.sendMessage(id, text("Child task"))
            service.getGenerationJobStateFlow(id).first { it == null }
            assertEquals("Live child progress", repo.getConversationById(id)!!.currentMessages.last().toText())
            assertTrue(service.errors.value.filter { it.conversationId == id }.joinToString("\n") { it.error.stackTraceToString() },
                service.errors.value.none { it.conversationId == id })
            val engine = GlobalContext.get().get<me.rerere.rikkahub.subagent.SubAgentEngine>()
            assertEquals("unknown_child", engine.sendToChild(id.toString(), Uuid.random().toString(), "wrong parent")["error"]?.jsonPrimitive?.content)
            assertEquals(id.toString(), engine.listChildren(parent.toString(), false).single()["conversation_id"]?.jsonPrimitive?.content)
            assertEquals("Live child progress", engine.childSnapshot(id.toString(), parent.toString())?.get("latest_reply")?.jsonPrimitive?.content)
            val active = CompletableDeferred<Unit>()
            provider.stream = { active.complete(Unit); awaitCancellation() }
            assertEquals(JsonPrimitive(true), engine.sendToChild(id.toString(), parent.toString(), "Follow-up")["accepted"])
            active.await()
            assertTrue(engine.cancelChild(id.toString(), parent.toString()))
            service.getGenerationJobStateFlow(id).first { it == null }
            assertFalse(engine.cancelChild(id.toString(), parent.toString()))
        }
    }

    @Test fun stopDuringResponseRetainsTheUpdateAndDoesNotLaunchAnotherRequest() = runBlocking {
        withChatFixture { service, provider, id, repo ->
            val queued = CompletableDeferred<Unit>()
            provider.stream = {
                emit(StreamChunk.ReasoningStart("r"))
                emit(StreamChunk.ReasoningDelta("r", "Still in the current request"))
                service.sendMessage(id, text("keep this update"))
                queued.complete(Unit)
                awaitCancellation()
            }
            service.sendMessage(id, text("original goal"))
            queued.await()
            service.stopGeneration(id)
            service.getGenerationJobStateFlow(id).first { it == null }
            assertEquals(1, provider.requests.size)
            val queue = service.getMessageQueueFlow(id).value
            assertTrue(queue.paused)
            assertFalse(queue.messages.single().isApplying)
            assertEquals(text("keep this update"), queue.messages.single().parts)
            assertEquals(1, requireNotNull(repo.getConversationById(id)).currentMessages.count { it.role == MessageRole.USER })
        }
    }

    @Test fun queueShowsWaitingApplyingAndPausedStates() {
        val state = mutableStateOf(MessageQueueState(messages = listOf(QueuedMessage(parts = text("Use Russian"), steerActiveTask = true))))
        compose.setContent { MaterialTheme { MessageQueuePanel(state.value, {}, { null }, { _, _ -> }, {}) } }
        compose.onNodeWithTag("chat_steering_status").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(messages = state.value.messages.map { it.copy(isApplying = true) }) }
        compose.onNodeWithTag("chat_steering_status").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(paused = true) }
        compose.onNodeWithTag("chat_steering_status").assertDoesNotExist()
    }
}
