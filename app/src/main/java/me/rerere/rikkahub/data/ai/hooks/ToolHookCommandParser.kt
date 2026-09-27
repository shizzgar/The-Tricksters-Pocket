package me.rerere.rikkahub.data.ai.hooks

/**
 * A bounded, conservative command-word reader, not a shell interpreter. Quoted arguments such as
 * `echo 'jadx failed'` do not become executable matches. Shell expansion and aliases are not resolved.
 */
internal object ToolHookCommandParser {
    private data class Word(val value: String, val separator: Boolean = false)
    private val shells = setOf("sh", "bash", "dash", "zsh", "ksh")

    fun executables(executable: String, arguments: List<String>): Set<String> = buildSet {
        addExecutable(executable)
        if (executable.substringAfterLast('/') in shells) {
            shellCommand(arguments)?.let { addAll(executables(it, depth = 1)) }
        }
    }

    fun executables(command: String): Set<String> = executables(command, depth = 0)

    private fun executables(command: String, depth: Int): Set<String> {
        if (depth > 2) return emptySet()
        // Complex shell programs require a literal command condition. Do not misidentify a
        // heredoc body, substitution, or function definition as an executed command.
        if (command.contains("<<") || command.contains("$(") || command.contains('`')) return emptySet()
        val words = tokenize(command.take(ToolHookEventNormalizer.MAX_OBSERVATION_CHARS)) ?: return emptySet()
        val result = linkedSetOf<String>()
        val segment = mutableListOf<String>()
        fun readSegment() {
            if (segment.isEmpty()) return
            var index = 0
            while (index < segment.size && isAssignment(segment[index])) index++
            if (index >= segment.size) return
            if (segment[index] == "env") {
                index++
                while (index < segment.size) {
                    val word = segment[index]
                    if (word == "-u" || word == "--unset" || word == "-C" || word == "--chdir") index += 2
                    else if (word.startsWith('-') || isAssignment(word)) index++
                    else break
                }
            }
            while (index < segment.size && segment[index] in setOf("command", "exec", "nohup")) {
                index++
                if (segment.getOrNull(index) == "--") index++
            }
            val commandWord = segment.getOrNull(index) ?: return
            // Dynamic command substitutions/variables cannot establish an executable identity.
            if (commandWord.any { it in "$`<>" }) return
            result.addExecutable(commandWord)
            if (commandWord.substringAfterLast('/') in shells) {
                shellCommand(segment.drop(index + 1))?.let { result.addAll(executables(it, depth + 1)) }
            }
        }
        for (word in words) {
            if (word.separator) {
                readSegment()
                segment.clear()
            } else segment += word.value
        }
        readSegment()
        return result
    }

    private fun shellCommand(arguments: List<String>): String? {
        for ((index, argument) in arguments.withIndex()) {
            if (argument == "--" || !argument.startsWith('-')) return null
            // --rcfile and other long options are not a -c command. Their parameter grammar
            // is deliberately not guessed; users can match the literal command instead.
            if (argument.startsWith("--")) return null
            if ('c' in argument.drop(1)) return arguments.getOrNull(index + 1)
        }
        return null
    }

    private fun MutableSet<String>.addExecutable(value: String) {
        if (value.isNotBlank()) {
            add(value)
            add(value.substringAfterLast('/'))
        }
    }

    private fun isAssignment(value: String): Boolean {
        val name = value.substringBefore('=', "")
        return name.isNotEmpty() && (name.first().isLetter() || name.first() == '_') &&
            name.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun tokenize(command: String): List<Word>? {
        val words = mutableListOf<Word>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var inWord = false
        var comment = false
        fun flush() {
            if (inWord) words += Word(token.toString())
            token.setLength(0)
            inWord = false
        }
        for (ch in command) {
            if (words.size >= 2_048) return null
            if (comment) {
                if (ch == '\n') { comment = false; words += Word("", separator = true) }
                continue
            }
            if (escaped) { token.append(ch); inWord = true; escaped = false; continue }
            if (quote == '\'') {
                if (ch == '\'') quote = null else token.append(ch)
                continue
            }
            if (ch == '\\') { escaped = true; inWord = true; continue }
            if (quote == '"') {
                if (ch == '"') quote = null else token.append(ch)
                continue
            }
            when {
                ch == '\'' || ch == '"' -> { quote = ch; inWord = true }
                ch == '#' && !inWord -> comment = true
                ch in "(){}" -> return null
                ch in ";|&\n" -> { flush(); words += Word("", separator = true) }
                ch.isWhitespace() -> flush()
                else -> { token.append(ch); inWord = true }
            }
        }
        if (quote != null || escaped) return null
        flush()
        return words
    }
}
