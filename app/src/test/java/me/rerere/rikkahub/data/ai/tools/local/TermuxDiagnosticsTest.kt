package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test

class TermuxDiagnosticsTest {
    @Test fun `recognizes observed failure classes without hiding failure`() {
        val cases = mapOf(
            "bash: line 18: getenforce: command not found" to "command_not_found",
            "strings: error: unknown argument '-e'" to "unsupported_option",
            "PermissionError: [Errno 13] Permission denied: 'capture.xml'" to "permission_denied",
            "xml.etree.ElementTree.ParseError: no element found: line 1, column 0" to "empty_or_invalid_xml",
            "grep: write error: Broken pipe" to "broken_pipe",
            "find: '/tmp/task': No such file or directory" to "missing_path",
            "Traceback (most recent call last):\nValueError: invalid literal for int()" to "script_error",
        )
        cases.forEach { (stderr, expected) ->
            assertEquals(expected, diagnoseTermuxFailure(1, stderr)?.code)
            assertNull(diagnoseTermuxFailure(0, stderr))
        }
    }

    @Test fun `does not invent a cause for an empty or unfamiliar error`() {
        assertNull(diagnoseTermuxFailure(1, ""))
        assertNull(diagnoseTermuxFailure(2, "probe failed"))
    }

    @Test fun `preview request cannot override configured stream ceilings`() {
        assertEquals(8_000, termuxPreviewLimit(null, 8_000))
        assertEquals(2_000, termuxPreviewLimit(0, 2_000))
        assertEquals(1_024, termuxPreviewLimit(1_024, 8_000))
        assertEquals(2_000, termuxPreviewLimit(Int.MAX_VALUE, 2_000))
        assertEquals(256, termuxPreviewLimit(1, 8_000))
    }
}
