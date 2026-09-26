package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.preferences.TermuxRuntime

/** Shared by captured commands and workspace console jobs; never applies to interactive sessions. */
internal fun termuxCommandPreamble(enabled: Boolean = TermuxRuntime.aptWrapEnabled): String =
    if (enabled) {
        "export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a; " +
            "apt(){ command apt -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
            "apt-get(){ command apt-get -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
            "export -f apt apt-get; "
    } else ""
