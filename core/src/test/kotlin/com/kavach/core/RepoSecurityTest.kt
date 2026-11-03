package com.kavach.core

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security and privacy gates that run with every `gradle test`, locally, in the pre-commit hook and in CI.
 * They enforce the spec's privacy promises mechanically: on-device first, no raw audio anywhere, no secrets in git.
 */
@Tag("security")
class RepoSecurityTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val skipDirs = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "node_modules", ".venv", "__pycache__")

    /** Files git tracks or would track (so a gitignored local.properties is fine). Falls back to a directory walk without git. */
    private val candidateFiles: List<File> by lazy {
        val viaGit = runCatching {
            val p = ProcessBuilder("git", "ls-files", "-co", "--exclude-standard").directory(root).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines(); if (p.waitFor() == 0) out else null
        }.getOrNull()
        viaGit?.map { File(root, it) }?.filter { it.isFile }
            ?: root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile }.toList()
    }

    private fun files(filter: (File) -> Boolean = { true }): Sequence<File> = candidateFiles.asSequence().filter(filter)

    private fun rel(f: File) = f.relativeTo(root).path

    private val textExt = setOf("kt", "kts", "java", "xml", "json", "md", "yml", "yaml", "toml", "properties", "py", "sh", "txt", "gradle")
    private fun textFiles() = files { it.extension in textExt || it.name in setOf(".gitignore") }.filter { it.length() < 2_000_000 }

    @Test
    fun `no secrets or credentials are committed`() {
        val patterns = mapOf(
            "Anthropic key" to Regex("""sk-ant-[A-Za-z0-9_\-]{20,}"""),
            "OpenAI-style key" to Regex("""\bsk-[A-Za-z0-9]{32,}\b"""),
            "Google API key" to Regex("""AIza[0-9A-Za-z_\-]{35}"""),
            "AWS access key" to Regex("""\bAKIA[0-9A-Z]{16}\b"""),
            "Groq key" to Regex("""\bgsk_[A-Za-z0-9]{20,}\b"""),
            "GitHub token" to Regex("""\bgh[pousr]_[A-Za-z0-9]{36,}\b"""),
            "private key block" to Regex("""-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"""),
            "hard-coded api key assignment" to Regex("""(?i)(api[_-]?key|secret|token)\s*[=:]\s*["'][A-Za-z0-9_\-]{24,}["']"""),
        )
        val hits = textFiles().flatMap { f ->
            val text = f.readText()
            patterns.filter { (_, rx) -> rx.containsMatchIn(text) }.map { "${rel(f)}: ${it.key}" }
        }.toList()
        assertTrue(hits.isEmpty(), "possible secrets committed:\n${hits.joinToString("\n")}")
    }

    @Test
    fun `no captured audio, keystores or local secret files are in the tree`() {
        val banned = setOf("wav", "mp3", "m4a", "pcm", "aac", "ogg", "keystore", "jks", "p12")
        val bannedNames = setOf("local.properties", ".env", "google-services.json", "secrets.properties")
        val bad = files { it.extension.lowercase() in banned || it.name in bannedNames || it.name.startsWith(".env.") }.map(::rel).toList()
        assertTrue(bad.isEmpty(), "files that must never be committed: $bad")
    }

    @Test
    fun `gitignore protects secrets and audio`() {
        val gi = File(root, ".gitignore").readText()
        listOf("local.properties", ".env", "*.keystore", "*.wav", "google-services.json", "secrets.properties").forEach {
            assertTrue(gi.lines().any { l -> l.trim() == it }, ".gitignore must list $it")
        }
    }

    @Test
    fun `no cleartext http anywhere except the on-device model on localhost`() {
        val rx = Regex("""http://(?!127\.0\.0\.1|localhost|10\.0\.2\.2|schemas\.android\.com|www\.w3\.org)[\w.\-]+""")
        val hits = files { it.extension in setOf("kt", "java", "xml", "json") && "src/main" in rel(it) && !rel(it).contains("scenarios/") }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> rx.containsMatchIn(l) && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") }.map { "${rel(f)}:${it.index + 1}: ${it.value.trim()}" } }
            .toList()
        assertTrue(hits.isEmpty(), "cleartext http:// found (only localhost is allowed):\n${hits.joinToString("\n")}")
    }

    @Test
    fun `the core never logs, so transcripts and numbers cannot leak into logcat or stdout`() {
        val rx = Regex("""\bprintln\(|\bprint\(|android\.util\.Log|\bLog\.[dviwe]\(|System\.(out|err)|printStackTrace""")
        val hits = files { it.extension == "kt" && rel(it).startsWith("core/src/main") }
            .filterNot { it.name == "TraceExplainer.kt" } // dev-only formatter, never invoked by the app path
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> rx.containsMatchIn(l) && !l.trimStart().startsWith("//") }.map { "${rel(f)}:${it.index + 1}" } }
            .toList()
        assertTrue(hits.isEmpty(), "logging in core main (privacy risk): $hits")
    }

    @Test
    fun `raw audio never reaches the llm layer or a network client`() {
        // The llm package only knows text. If anyone adds a ByteArray/ShortArray/AudioRecord parameter here, this fails.
        val rx = Regex("""ByteArray|ShortArray|FloatArray|AudioRecord|\.wav|base64""", RegexOption.IGNORE_CASE)
        val allowed = setOf("toByteArray") // UTF-8 encoding of the JSON body in the HTTP transport
        val hits = files { it.extension == "kt" && rel(it).startsWith("core/src/main/kotlin/com/kavach/core/llm") }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> rx.containsMatchIn(l.replace("toByteArray", "")) && allowed.none { a -> l.trim() == a } }.map { "${rel(f)}:${it.index + 1}: ${it.value.trim()}" } }
            .toList()
        assertTrue(hits.isEmpty(), "audio-like types in the LLM/network layer:\n${hits.joinToString("\n")}")
    }

    @Test
    fun `cloud fallback is off by default`() {
        // The routing client's default is cloudAllowed = false; the app must not flip it without an explicit user toggle.
        val src = File(root, "core/src/main/kotlin/com/kavach/core/llm/LlmClient.kt").readText()
        assertTrue(Regex("""cloudAllowed:\s*\(\)\s*->\s*Boolean\s*=\s*\{\s*false\s*\}""").containsMatchIn(src), "RoutingClient must default cloudAllowed to false")
    }

    @Test
    fun `android manifest requests only documented permissions and is hardened`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        if (!manifest.exists()) return // activates as soon as the :app module lands
        val text = manifest.readText()
        val allowed = setOf(
            "READ_PHONE_STATE", "READ_CALL_LOG", "READ_CONTACTS", "RECORD_AUDIO", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE",
            "POST_NOTIFICATIONS", "SYSTEM_ALERT_WINDOW", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "INTERNET", "CALL_PHONE", "USE_FULL_SCREEN_INTENT",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "WAKE_LOCK", "VIBRATE", "FOREGROUND_SERVICE_SPECIAL_USE", "BIND_ACCESSIBILITY_SERVICE",
            "BIND_NOTIFICATION_LISTENER_SERVICE", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE_PHONE_CALL", "READ_PHONE_NUMBERS",
        )
        val requested = Regex("""uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet(), requested - allowed, "undocumented permissions requested")
        assertTrue(text.contains("""android:allowBackup="false""""), "allowBackup must be false: the context store holds call data")
        assertFalse(text.contains("""usesCleartextTraffic="true""""), "global cleartext traffic must stay off")
        // Components with intent filters must declare exported explicitly (Android 12+), and only the launcher/services that need it.
        Regex("""<(activity|service|receiver)\b[^>]*>""").findAll(text).forEach { m ->
            val tag = m.value
            assertTrue("android:exported=" in tag || m.groupValues[1] == "activity" && "intent-filter" !in text.substringAfter(tag).substringBefore("</${m.groupValues[1]}>") ,
                "component without explicit exported: ${tag.take(120)}")
        }
    }

    @Test
    fun `android network security config allows cleartext only to localhost`() {
        val cfg = File(root, "app/src/main/res/xml/network_security_config.xml")
        if (!cfg.exists()) return
        val text = cfg.readText()
        assertTrue(text.contains("""cleartextTrafficPermitted="false"""") || !text.contains("""cleartextTrafficPermitted="true""""), "base config must forbid cleartext")
        val domains = Regex("""<domain[^>]*>([^<]+)</domain>""").findAll(text).map { it.groupValues[1].trim() }.toSet()
        assertEquals(emptySet(), domains - setOf("127.0.0.1", "localhost"), "cleartext is only allowed to the on-device Ollama")
    }
}
