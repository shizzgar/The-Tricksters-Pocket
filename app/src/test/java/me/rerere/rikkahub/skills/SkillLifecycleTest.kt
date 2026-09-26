package me.rerere.rikkahub.skills

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private fun files(body: String = "old", extras: Map<String, ByteArray> = emptyMap()) = mapOf(
        "SKILL.md" to "---\nname: reports\ndescription: Reports\nversion: 2\nrequires-tools: [read_file]\nrequires-env: [python3]\n---\n$body\n".toByteArray()
    ) + extras
    @Test fun `preview never changes installed files and default install rejects collision`() {
        val root = temp.newFolder(); val state = temp.newFolder(); val lifecycle = SkillLifecycle(root, state)
        lifecycle.install(lifecycle.preview("reports", files(), "original"), SkillInstallChoice.NEW)
        val old = File(root, "reports/SKILL.md").readBytes()
        val preview = lifecycle.preview("reports", files("candidate"), "remote")
        assertArrayEquals(old, File(root, "reports/SKILL.md").readBytes())
        assertNotNull(preview.expectedRevision)
        assertEquals("2", preview.version)
        assertEquals(listOf("read_file"), preview.requirements.tools)
        assertTrue(runCatching { lifecycle.install(preview, SkillInstallChoice.NEW) }.isFailure)
        assertArrayEquals(old, File(root, "reports/SKILL.md").readBytes())
    }
    @Test fun `update retains binary previous version and restore also restores origin`() {
        val root = temp.newFolder(); val state = temp.newFolder(); val lifecycle = SkillLifecycle(root, state)
        val original = files(extras = mapOf("asset.bin" to byteArrayOf(0,-1,5)))
        lifecycle.install(lifecycle.preview("reports", original, "original"), SkillInstallChoice.NEW)
        val preview = lifecycle.preview("reports", files("new"), "updated")
        assertEquals(setOf("SKILL.md", "asset.bin"), preview.changes.map { it.path }.toSet())
        lifecycle.install(preview, SkillInstallChoice.UPDATE)
        assertFalse(File(root,"reports/asset.bin").exists())
        val workspace = SkillWorkspace(File(root,"reports"),state)
        assertTrue(workspace.snapshot().canRestore)
        workspace.restore(workspace.snapshot().revision)
        assertArrayEquals(original.getValue("asset.bin"), File(root,"reports/asset.bin").readBytes())
        assertTrue(File(root,"reports/.pocket-origin.json").readText().contains("original"))
    }
    @Test fun `update rejects changes after preview and does not replace backup`() {
        val root = temp.newFolder(); val state = temp.newFolder(); val lifecycle = SkillLifecycle(root, state)
        lifecycle.install(lifecycle.preview("reports",files(),"local"),SkillInstallChoice.NEW)
        val candidate = lifecycle.preview("reports",files("imported"),"URL")
        val workspace = SkillWorkspace(File(root,"reports"),state)
        workspace.write("notes.txt","newer edit".toByteArray(),workspace.snapshot().revision,create=true)
        val current = workspace.snapshot()
        assertTrue(runCatching { lifecycle.install(candidate,SkillInstallChoice.UPDATE) }.exceptionOrNull() is SkillConflict)
        assertEquals(current.revision, workspace.snapshot().revision)
        assertEquals("newer edit",workspace.open("notes.txt").text)
        workspace.restore(current.revision)
        assertFalse(File(root,"reports/notes.txt").exists())
    }
    @Test fun `new import race rejects second writer and copy preserves original`() {
        val root=temp.newFolder();val state=temp.newFolder();val lifecycle=SkillLifecycle(root,state)
        val a=lifecycle.preview("reports",files("a"),"a")
        val b=lifecycle.preview("reports",files("b"),"b")
        lifecycle.install(a,SkillInstallChoice.NEW)
        assertTrue(runCatching { lifecycle.install(b,SkillInstallChoice.NEW) }.isFailure)
        lifecycle.install(b,SkillInstallChoice.COPY,"reports-copy")
        assertTrue(File(root,"reports/SKILL.md").readText().contains("\na\n"))
        assertTrue(File(root,"reports-copy/SKILL.md").readText().contains("name: reports-copy"))
        assertTrue(runCatching { lifecycle.install(b,SkillInstallChoice.COPY,"reports-copy") }.isFailure)
    }
    @Test fun `invalid candidate leaves package and rollback untouched`() {
        val root=temp.newFolder();val state=temp.newFolder();val lifecycle=SkillLifecycle(root,state)
        lifecycle.install(lifecycle.preview("reports",files(),"local"),SkillInstallChoice.NEW)
        listOf("../escape", ".pocket-origin.json", "sub/.seeded").forEach { bad ->
            assertTrue(runCatching { lifecycle.preview("reports",files(extras=mapOf(bad to byteArrayOf(1))),"remote") }.isFailure)
        }
        assertFalse(SkillWorkspace(File(root,"reports"),state).snapshot().canRestore)
    }
    @Test fun `test history round trips bounds and revisions without claiming correctness`() {
        val history=SkillTestHistory(File(temp.newFolder(),"tests.json"))
        repeat(15) { history.append(SkillTestRecord("revision-$it","assistant","same prompt",it.toLong(),"response_received","output")) }
        val reloaded=SkillTestHistory(File(temp.root.listFiles()!!.first(),"tests.json")).read()
        assertEquals(12,reloaded.size)
        assertEquals("revision-3",reloaded.first().revision)
        assertEquals("same prompt",reloaded.last().prompt)
        assertEquals("response_received",reloaded.last().outcome)
    }
    @Test fun `requirements support alternatives without promising environment readiness`() {
        val requirements=SkillRequirements.parse("---\nname: reports\ndescription: Reports\nrequires-tools: [read_file]\nrequires-any-tools: [termux_run_command, workspace_shell]\nrequires-env: [python3]\n---")
        assertTrue(requirements.missingTools(setOf("read_file","workspace_shell")).isEmpty())
        assertEquals(listOf("python3"),requirements.environment)
        assertEquals(2,requirements.missingTools(emptySet()).size)
    }
    @Test fun `content diff shows a change after a long unchanged prefix`() {
        val prefix = "unchanged line\n".repeat(2000)
        val diff = SkillFileChange("SKILL.md", (prefix + "old\n").toByteArray(), (prefix + "new\n").toByteArray()).preview()
        assertTrue(diff.contains("−old"))
        assertTrue(diff.contains("+new"))
        assertTrue(diff.length < 1000)
    }
}
