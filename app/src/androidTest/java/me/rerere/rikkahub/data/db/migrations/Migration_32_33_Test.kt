package me.rerere.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Migration_32_33_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun oldChatsSurviveAndChildRelationshipSurvivesReopen() = runBlocking {
        val name = "migration-32-33"
        helper.createDatabase(name, 32).apply {
            execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at) VALUES ('parent','Existing chat','[]',1,2)")
            close()
        }
        helper.runMigrationsAndValidate(name, 33, true).apply {
            query("SELECT title,parent_conversation_id,subagent_run_id FROM ConversationEntity WHERE id='parent'").use {
                assertTrue(it.moveToFirst()); assertEquals("Existing chat", it.getString(0))
                assertEquals("", it.getString(1)); assertEquals("", it.getString(2))
            }
            execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at,parent_conversation_id,subagent_run_id,parent_tool_call_id) VALUES ('child','Specialist','[]',3,4,'parent','run','call')")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val child = reopened.conversationDao().getBySubAgentRunId("run")!!
            assertEquals("parent", child.parentConversationId)
            assertEquals("call", child.parentToolCallId)
            assertEquals(listOf(child), reopened.conversationDao().observeChildren("parent").first())
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
