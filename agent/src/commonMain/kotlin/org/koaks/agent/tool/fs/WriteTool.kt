package org.koaks.agent.tool.fs

import kotlinx.serialization.Serializable
import org.koaks.agent.platform.PlatformFileSystem
import org.koaks.agent.tool.displayName
import org.koaks.agent.tool.executeToolSafely
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.framework.tool.Tool
import org.koaks.runtime.resource.AccessMode
import org.koaks.runtime.resource.withRuntimeResource

@Serializable
internal data class WriteInput(
    val path: String,
    val content: String,
)

internal class WriteTool(
    private val policy: WorkspaceAccessPolicy = WorkspaceAccessPolicy(),
) : Tool<WriteInput> {
    override val name: String = "Write"
    override val description: String =
        "Create a new file, or overwrite an existing file, with the given `content`. " +
            "`path` may be relative to the current working directory and parent directories must already exist. " +
            "Use this to author whole files; to change part of an existing file prefer the `Edit` tool. " +
            "The write is atomic and returns a short confirmation with the file's line and byte counts."
    override val inputSerializer = WriteInput.serializer()
    override val hasSideEffects: Boolean = true

    override suspend fun execute(input: WriteInput): String =
        executeToolSafely(name) {
            val requestedPath = input.path.trim()
            if (requestedPath.isEmpty()) return@executeToolSafely "Error: path is required."
            if (input.content.length > policy.maxWriteChars) {
                return@executeToolSafely buildString {
                    append("Error: content exceeds the ${policy.maxWriteChars} character limit; ")
                    append("split the work or use the shell tool.")
                }
            }
            val path = policy.resolveWrite(requestedPath)

            val existed = PlatformFileSystem.fileExists(path)
            val write =
                withRuntimeResource("file:$path", AccessMode.WRITE) {
                    PlatformFileSystem.writeWholeFile(path, input.content)
                }
            if (write.error != null) return@executeToolSafely "Error: ${write.error}"

            val lineCount =
                when {
                    input.content.isEmpty() -> 0
                    input.content.endsWith("\n") -> input.content.count { it == '\n' }
                    else -> input.content.count { it == '\n' } + 1
                }
            val verb = if (existed) "Overwrote" else "Created"
            return@executeToolSafely buildString {
                append("✓ $verb ${displayName(path)}")
                append("  ($lineCount lines, ${write.bytesWritten} bytes)")
            }
        }
}
