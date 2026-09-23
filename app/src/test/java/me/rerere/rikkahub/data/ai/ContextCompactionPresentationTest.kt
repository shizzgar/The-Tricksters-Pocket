package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ContextCompactionPresentationTest {
    @Test fun `translation and completion bookkeeping do not invalidate compression`() {
        val reasoning = UIMessagePart.Reasoning("Observed reasoning", finishedAt = null)
        val message = UIMessage.assistant("Verified result").copy(parts = listOf(
            reasoning, UIMessagePart.Text("Verified result"),
        ))
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(message))))
        val updated = source.updateCurrentMessages(listOf(message.copy(
            translation = "Перевод результата", finishedAt = message.createdAt,
            parts = listOf(reasoning.copy(finishedAt = kotlin.time.Instant.fromEpochMilliseconds(1000)), UIMessagePart.Text("Verified result")),
        )))
        assertTrue(ContextCompactionPresentation.sourcePrefixUnchanged(source, updated, 1))
    }

    @Test fun `same-id tool result and input edits still invalidate the source`() {
        val tool = UIMessagePart.Tool("call", "termux_run_command", "{}", output = listOf(UIMessagePart.Text("old evidence")))
        val message = UIMessage.assistant("").copy(parts = listOf(tool))
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(message))))
        for (changed in listOf(tool.copy(input = "{\"command\":\"changed\"}"), tool.copy(output = listOf(UIMessagePart.Text("new evidence"))))) {
            val after = source.updateCurrentMessages(listOf(message.copy(parts = listOf(changed))))
            assertFalse(ContextCompactionPresentation.sourcePrefixUnchanged(source, after, 1))
            assertEquals("source_content_changed:0", ContextCompactionPresentation.sourcePrefixChangeReason(source, after, 1))
        }
    }

    @Test fun `tail updates are allowed but branch switches and attachments are not`() {
        val original = UIMessage.user("Compress this").copy(parts = listOf(UIMessagePart.Image("file://original.png")))
        val other = UIMessage.user("Different branch")
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(original, other)), MessageNode(messages = listOf(UIMessage.user("raw tail"))),
        ))
        val tailChanged = source.copy(messageNodes = source.messageNodes.take(1) + MessageNode(messages = listOf(UIMessage.user("new raw tail"))))
        assertTrue(ContextCompactionPresentation.sourcePrefixUnchanged(source, tailChanged, 1))
        val switched = source.copy(messageNodes = listOf(source.messageNodes[0].copy(selectIndex = 1)) + source.messageNodes.drop(1))
        assertEquals("source_branch_changed:0", ContextCompactionPresentation.sourcePrefixChangeReason(source, switched, 1))
        val attachmentChanged = source.updateCurrentMessages(listOf(original.copy(parts = listOf(UIMessagePart.Image("file://replacement.png")))))
        assertFalse(ContextCompactionPresentation.sourcePrefixUnchanged(source, attachmentChanged, 1))
    }

    @Test fun `cancel button targets its own operation only`() {
        val parent = kotlinx.coroutines.Job()
        val operation = kotlinx.coroutines.Job(parent)
        val other = kotlinx.coroutines.Job(parent)
        ContextCompactionPresentation.register("operation", operation)
        try {
            assertTrue(ContextCompactionPresentation.canCancel("operation"))
            ContextCompactionPresentation.cancel("operation")
            assertTrue(operation.isCancelled)
            assertTrue(other.isActive)
            assertTrue(parent.isActive)
        } finally {
            ContextCompactionPresentation.unregister("operation")
            parent.cancel()
        }
    }

    @Test fun `progress updates keep source valid but same-id text edits invalidate it`() {
        val message = UIMessage.assistant("Original observation")
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(message))))
        val event = ContextCompactionPresentation.startTool(false, 100, 100, 1000, 500)
        val withEvent = ContextCompactionPresentation.attachToMessage(conversation, message.id, event)
        assertTrue(ContextCompactionPresentation.sourcePrefixUnchanged(conversation, withEvent, 1))
        val changed = withEvent.updateCurrentMessages(listOf(message.copy(parts = listOf(UIMessagePart.Text("Correction")))))
        assertFalse(ContextCompactionPresentation.sourcePrefixUnchanged(conversation, changed, 1))
        assertFalse(ContextCompactionPresentation.sourcePrefixUnchanged(conversation, conversation.copy(messageNodes = emptyList()), 1))
    }

    @Test fun `manual event replaces its running card and remains display only`() {
        val user = UIMessage.user("Keep the current objective")
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(user))))
        val running = ContextCompactionPresentation.startTool(false, 10_000, 5_000, 10_000, 2_000)
        val started = ContextCompactionPresentation.attachToMessage(conversation, user.id, running)
        val finished = ContextCompactionPresentation.completeTool(running, sampleCompaction(conversation).copy(isAuto = false), 1234)
        val ended = ContextCompactionPresentation.attachToMessage(started, user.id, finished)
        assertEquals(1, ended.currentMessages.single().parts.filterIsInstance<UIMessagePart.Tool>().size)
        assertTrue(finished.input.contains("\"mode\":\"manual\""))
        assertTrue(finished.input.contains("\"elapsed_ms\":1234"))
        assertEquals(running.toolCallId, finished.toolCallId)
        assertFalse(ContextCompactionPresentation.hasAutomaticDisplayTool(ended.currentMessages.single()))
        assertEquals(listOf(user), ContextCompactionPresentation.stripDisplayTools(ended.currentMessages))
    }

    @Test
    fun `display-only compaction tool is retained in chat but removed from request view`() {
        val assistant = UIMessage.assistant("tool completed")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(assistant))),
        )
        val presentationTool = ContextCompactionPresentation.createTool(
            compaction = sampleCompaction(conversation),
        )

        val displayedConversation = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = assistant.id,
            tool = presentationTool,
        )
        val displayedMessage = displayedConversation.currentMessages.single()
        assertTrue(ContextCompactionPresentation.hasDisplayTool(displayedMessage))

        val requestView = ContextCompactionView.build(displayedConversation, compaction = null)
        assertFalse(ContextCompactionPresentation.hasDisplayTool(requestView.messages.single()))
        assertEquals("tool completed", requestView.messages.single().toText())
    }

    @Test
    fun `stream updates preserve an attached compaction tool`() {
        val history = MessageNode(messages = listOf(UIMessage.user("earlier context")))
        val assistant = UIMessage.assistant("tool completed")
        val assistantNode = MessageNode(messages = listOf(assistant))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(history, assistantNode),
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "compressed context",
            tailStartNodeId = assistantNode.id,
            sourceEndNodeId = history.id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 12_345,
            createdAt = Instant.now(),
        )
        val withCard = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = assistant.id,
            tool = ContextCompactionPresentation.createTool(compaction),
        )
        val requestView = ContextCompactionView.build(withCard, compaction)
        val streamedUpdate = assistant.copy(parts = listOf(UIMessagePart.Text("tool completed with final state")))

        val merged = ContextCompactionView.mergeGeneratedMessages(
            conversation = withCard,
            view = requestView,
            generatedMessages = listOf(requestView.messages.first(), streamedUpdate),
        )

        assertEquals(
            listOf("tool completed with final state"),
            merged.currentMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .map { it.text },
        )
        assertTrue(ContextCompactionPresentation.hasDisplayTool(merged.currentMessages.last()))
    }

    @Test
    fun `stream updates keep compaction card before parts emitted afterwards`() {
        val initial = UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("search results"),
                UIMessagePart.Tool(
                    toolCallId = "search-before-compaction",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("first result")),
                ),
            ),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(initial))),
        )
        val card = ContextCompactionPresentation.createTool(sampleCompaction(conversation))
        val withCard = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = initial.id,
            tool = card,
        )
        val replacement = initial.copy(
            parts = initial.parts + UIMessagePart.Tool(
                toolCallId = "search-after-compaction",
                toolName = "web_fetch",
                input = "{}",
                output = listOf(UIMessagePart.Text("second result")),
            ),
        )

        val restored = ContextCompactionPresentation.preserveDisplayTools(
            previous = withCard.currentMessages.single(),
            replacement = replacement,
        )

        assertEquals(
            listOf(
                "search_web",
                ContextCompactionPresentation.TOOL_NAME,
                "web_fetch",
            ),
            restored.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName },
        )
    }

    @Test
    fun `compaction card reports retained raw tool count`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("earlier context")))),
        )
        val compaction = sampleCompaction(conversation).copy(
            summary = "compressed context\n\n" +
                "[Raw context retained verbatim after this summary]\n" +
                "raw_messages=1\ncompleted_tool_calls=55\n" +
                "[End raw context retention report]"
        )

        val tool = ContextCompactionPresentation.createTool(compaction)

        assertTrue(tool.input.contains("\"retained_raw_tool_calls\":55"))
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("completed_tool_calls=55"))
    }

    private fun sampleCompaction(conversation: Conversation) = ConversationCompaction(
        conversationId = conversation.id,
        summary = "compressed context",
        tailStartNodeId = null,
        sourceEndNodeId = conversation.messageNodes.single().id,
        summaryModelId = Uuid.random(),
        isAuto = true,
        sourceTokenEstimate = 12_345,
        createdAt = Instant.now(),
    )
}
