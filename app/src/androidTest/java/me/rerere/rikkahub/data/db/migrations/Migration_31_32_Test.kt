package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Migration_31_32_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun existingLinuxWorkspaceAndApprovalSettingsSurviveMigration() {
        val name = "migration-31-32"
        helper.createDatabase(name, 31).apply {
            execSQL("INSERT INTO workspaces (id,name,root,shell_status,created_at,updated_at,tool_approvals,shell_compatibility_mode) VALUES ('w','Project','w','READY',1,2,'{\"workspace_shell\":false}',1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 32, true)
        db.query("SELECT name,termux_path,tool_approvals,shell_compatibility_mode FROM workspaces WHERE id='w'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Project", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals("{\"workspace_shell\":false}", it.getString(2))
            assertEquals(1, it.getInt(3))
        }
        db.close()
    }
}
