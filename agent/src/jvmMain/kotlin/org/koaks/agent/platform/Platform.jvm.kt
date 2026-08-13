package org.koaks.agent.platform

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

public actual object PlatformEnvironment : Environment {
    override fun get(key: String): String? = System.getenv(key)
}

public actual object PlatformInfo {
    public actual val operatingSystemName: String
        get() =
            when {
                System.getProperty("os.name").contains("win", ignoreCase = true) -> "Windows"
                System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macOS"
                System.getProperty("os.name").contains("linux", ignoreCase = true) -> "Linux"
                else -> System.getProperty("os.name")
            }

    public actual fun workingDirectory(): String = System.getProperty("user.dir")
}

internal actual object PlatformPathResolver {
    public actual fun canonicalPath(path: String): String? =
        runCatching {
            val input = Path.of(path)
            if (Files.exists(input)) {
                input.toRealPath().toString()
            } else {
                val missing = mutableListOf<String>()
                var current: Path? = input
                while (current != null && !Files.exists(current)) {
                    current.fileName?.toString()?.let(missing::add)
                    current = current.parent
                }
                val existing = current?.toRealPath() ?: return@runCatching null
                missing
                    .asReversed()
                    .fold(existing) { base, segment -> base.resolve(segment) }
                    .normalize()
                    .toString()
            }
        }.getOrNull()
}

internal actual object ConfigFileSystem {
    public actual fun createDirectory(path: String): Boolean =
        runCatching {
            Files.createDirectories(Path.of(path))
            true
        }.getOrDefault(false)
}

internal actual object PlatformFileSystem {
    public actual fun workingDirectory(): String = PlatformInfo.workingDirectory()

    public actual fun canonicalPath(path: String): String? = PlatformPathResolver.canonicalPath(path)

    public actual fun renamePath(
        from: String,
        to: String,
    ): Boolean =
        runCatching {
            Files.move(Path.of(from), Path.of(to), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)

    public actual fun removePath(path: String): Boolean =
        runCatching {
            Files.deleteIfExists(Path.of(path))
        }.getOrDefault(false)

    public actual fun createDirectory(path: String): Boolean = ConfigFileSystem.createDirectory(path)

    public actual fun timestamp(): Long = System.currentTimeMillis() / 1000L

    public actual fun readTextWindow(
        path: String,
        offset: Int,
        limit: Int,
        maxCapturedChars: Int,
    ): TextWindowScan =
        runCatching {
            val lines = mutableListOf<NumberedTextLine>()
            var totalLines = 0L
            var totalChars = 0L
            var capturedChars = 0
            var truncated = false
            Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(8_192)
                val line = StringBuilder()

                fun completeLine() {
                    totalLines++
                    if (totalLines < offset || lines.size >= limit || truncated) {
                        line.clear()
                        return
                    }
                    val text = line.toString().removeSuffix("\r")
                    val remaining = maxCapturedChars - capturedChars
                    if (remaining <= 0) {
                        truncated = true
                    } else {
                        val captured = text.take(remaining)
                        lines += NumberedTextLine(totalLines, captured)
                        capturedChars += captured.length
                        if (captured.length < text.length) truncated = true
                    }
                    line.clear()
                }

                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val character = buffer[index]
                        totalChars++
                        if (character == '\n') completeLine() else line.append(character)
                    }
                }
                if (line.isNotEmpty()) completeLine()
            }
            TextWindowScan(totalLines, totalChars, lines, truncated)
        }.getOrElse { error ->
            TextWindowScan(0, 0, emptyList(), false, error.message ?: "unable to read file: $path")
        }

    public actual fun fileExists(path: String): Boolean = runCatching { Files.isRegularFile(Path.of(path)) }.getOrDefault(false)

    public actual fun readWholeFile(
        path: String,
        maxBytes: Long,
    ): FileContent =
        runCatching {
            val file = Path.of(path)
            val size = Files.size(file)
            if (size > maxBytes) {
                return@runCatching FileContent(null, size, "file is too large to edit (over $maxBytes bytes): $path")
            }
            FileContent(Files.readAllBytes(file).toString(StandardCharsets.UTF_8), size)
        }.getOrElse { error ->
            FileContent(null, 0, error.message ?: "unable to open file: $path")
        }

    public actual fun writeWholeFile(
        path: String,
        content: String,
    ): FileWriteResult =
        runCatching {
            val target = Path.of(path)
            val temporary = Path.of("$path.koaks-tmp")
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.deleteIfExists(target)
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            FileWriteResult(bytes.size.toLong())
        }.getOrElse { error ->
            FileWriteResult(0, error.message ?: "failed to write file: $path")
        }
}

internal actual object BashCommandLine {
    private val windows: Boolean
        get() = PlatformInfo.operatingSystemName == "Windows"

    public actual val toolName: String = if (windows) "PowerShell" else "Bash"
    public actual val shellName: String = if (windows) "PowerShell (`powershell.exe`)" else "Bash (`bash`)"
    public actual val commandSyntaxGuidance: String =
        if (windows) {
            "On Windows, use PowerShell syntax and cmdlets only."
        } else {
            "Use Bash command syntax."
        }

    public actual fun execute(
        command: String,
        maxOutputChars: Int,
        timeoutMillis: Long,
    ): CommandResult {
        val commandLine =
            if (windows) {
                listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command)
            } else {
                listOf("bash", "-lc", command)
            }
        val process =
            try {
                ProcessBuilder(commandLine)
                    .redirectErrorStream(true)
                    .apply {
                        environment().keys.retainAll(ALLOWED_ENVIRONMENT)
                    }.start()
            } catch (error: Exception) {
                val message = "Unable to start $shellName: ${error.message ?: "process launch failed"}"
                return CommandResult(1, message, message.length, false)
            }

        val capture = BoundedCapture(maxOutputChars)
        val reader =
            thread(start = true, isDaemon = true, name = "koaks-shell-output") {
                process.inputStream.use { input -> input.copyTo(capture) }
            }
        val completed = process.waitFor(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(2_000)
        val rawOutput = capture.text()
        val output = rawOutput.take(maxOutputChars.coerceAtLeast(0))
        return CommandResult(
            status = if (completed) process.exitValue() else 124,
            output = output,
            totalOutputChars = capture.totalChars,
            truncated = capture.truncated || rawOutput.length > output.length,
        )
    }
}

private class BoundedCapture(
    maxChars: Int,
) : java.io.OutputStream() {
    private val maxChars = maxChars.coerceAtLeast(0)
    private val bytes = ByteArrayOutputStream()
    var totalBytes: Long = 0
        private set

    override fun write(value: Int) {
        totalBytes++
        if (bytes.size() < maxChars * 4) bytes.write(value)
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        totalBytes += length
        val remaining = (maxChars * 4 - bytes.size()).coerceAtLeast(0)
        if (remaining > 0) bytes.write(buffer, offset, minOf(length, remaining))
    }

    fun text(): String = bytes.toByteArray().toString(StandardCharsets.UTF_8)

    val totalChars: Int
        get() = totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val truncated: Boolean
        get() = totalBytes > bytes.size()
}

private val ALLOWED_ENVIRONMENT =
    setOf(
        "PATH",
        "PATHEXT",
        "SystemRoot",
        "COMSPEC",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "HOMEDRIVE",
        "HOMEPATH",
        "APPDATA",
        "LOCALAPPDATA",
        "PROGRAMDATA",
        "ProgramFiles",
        "ProgramFiles(x86)",
        "JAVA_HOME",
        "HOME",
        "LANG",
        "TERM",
    )
