package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Migration_33_34_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun oldMemoriesKeepContentAndGainReversibleProvenance() {
        val name = "migration-memory-33-34"
        helper.createDatabase(name, 33).apply {
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES (7,'assistant','Preserve this preference')")
            close()
        }
        try {
            helper.runMigrationsAndValidate(name, 34, true).use { db ->
                db.query("SELECT content,source_conversation_id,source_message_id,updated_at,revision,history,deleted FROM MemoryEntity WHERE id=7").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Preserve this preference", it.getString(0))
                    assertTrue(it.isNull(1))
                    assertTrue(it.isNull(2))
                    assertEquals(0L, it.getLong(3))
                    assertEquals(0, it.getInt(4))
                    assertEquals("[]", it.getString(5))
                    assertEquals(0, it.getInt(6))
                }
                db.execSQL("UPDATE MemoryEntity SET source_conversation_id='chat',source_message_id='message',updated_at=123,revision=1,history='[]',deleted=1 WHERE id=7")
                db.query("SELECT source_conversation_id,source_message_id,updated_at,revision,deleted FROM MemoryEntity WHERE id=7").use {
                    assertTrue(it.moveToFirst()); assertEquals("chat", it.getString(0)); assertEquals("message", it.getString(1))
                    assertEquals(123L,it.getLong(2)); assertEquals(1,it.getInt(3)); assertEquals(1,it.getInt(4))
                }
            }
        } finally { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }
    }
}
