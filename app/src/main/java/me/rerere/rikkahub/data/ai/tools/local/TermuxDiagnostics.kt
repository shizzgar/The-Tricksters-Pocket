package me.rerere.rikkahub.data.ai.tools.local

/** Evidence-based suggestions only. Never rewrite a command, retry a mutation or hide its exit code. */
internal data class TermuxDiagnostic(val code: String, val hint: String)

internal fun diagnoseTermuxFailure(exitCode: Int, stderr: String): TermuxDiagnostic? {
    if (exitCode == 0) return null
    val error = stderr.takeLast(16_384).lowercase()
    return when {
        "permission denied" in error || "permission denial" in error -> TermuxDiagnostic(
            "permission_denied", "Check the effective UID and ownership of the exact path or Android command. Root-created files may not be readable as the Termux UID. Do not relax global permissions or SELinux; repair only the established access issue.",
        )
        "command not found" in error || "inaccessible or not found" in error -> TermuxDiagnostic(
            "command_not_found", "Check command -v and the installed tool's --help. Android system tools may live in /system/bin; Termux is not a desktop Linux distribution. Do not reinstall the toolchain for a missing command without checking its path.",
        )
        listOf("unknown argument", "unknown option", "unrecognized option", "invalid option").any { it in error } -> TermuxDiagnostic(
            "unsupported_option", "Inspect this executable's --help/version once and adapt to its actual implementation (Android, LLVM, BusyBox or GNU). Do not repeat the same unsupported flag.",
        )
        "parseerror: no element found" in error || "parseerror: syntax error: line 1, column 0" in error -> TermuxDiagnostic(
            "empty_or_invalid_xml", "The XML parser did not receive a valid document. Check the producer's exit code and a bounded file preview before parsing. Retry only after fixing the producer; an empty UI dump is not evidence that the target has no elements.",
        )
        "broken pipe" in error -> TermuxDiagnostic(
            "broken_pipe", "A downstream reader may have closed a pipe early (for example head under pipefail). Inspect pipeline statuses and the bounded output. Prefer a tool's own match limit or a saved file. Do not globally disable pipefail or treat partial output as verified success.",
        )
        "no such file or directory" in error -> TermuxDiagnostic(
            "missing_path", "Verify the producer completed and check the resolved path. Use a task directory or the configured Termux temporary directory; do not assume desktop /tmp exists. Check before running dependent commands.",
        )
        "traceback (most recent call last)" in error -> TermuxDiagnostic(
            "script_error", "Fix the first failing parser/type/quoting operation and test it on a small saved input. Pass shell variables as script arguments or environment values; quoted heredocs do not expand them. Do not rerun the entire experiment to test a local script fix.",
        )
        else -> null
    }
}

/** A per-call preview can be reduced, never expanded beyond the user's configured cap. */
internal fun termuxPreviewLimit(requested: Int?, configured: Int): Int =
    requested?.takeIf { it > 0 }?.coerceIn(256.coerceAtMost(configured), configured) ?: configured
