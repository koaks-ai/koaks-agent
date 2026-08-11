package org.koaks.agent.platform

internal actual object SystemCredentialStore {
    public actual fun read(name: String): String? {
        val target = name.replace("'", "''")
        val script =
            """
            ${'$'}source = @'
            using System;
            using System.Runtime.InteropServices;
            public static class KoaksWinCred {
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                public struct Credential {
                    public UInt32 Flags;
                    public UInt32 Type;
                    public string TargetName;
                    public string Comment;
                    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
                    public UInt32 CredentialBlobSize;
                    public IntPtr CredentialBlob;
                    public UInt32 Persist;
                    public UInt32 AttributeCount;
                    public IntPtr Attributes;
                    public string TargetAlias;
                    public string UserName;
                }
                [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
                public static extern bool CredRead(string target, UInt32 type, UInt32 flags, out IntPtr credential);
                [DllImport("advapi32.dll", SetLastError = true)]
                public static extern void CredFree(IntPtr credential);
            }
            '@
            [void](Add-Type -TypeDefinition ${'$'}source -ErrorAction Stop)
            ${'$'}pointer = [IntPtr]::Zero
            if (-not [KoaksWinCred]::CredRead('$target', 1, 0, [ref]${'$'}pointer)) { exit 1 }
            try {
                ${'$'}credential = [Runtime.InteropServices.Marshal]::PtrToStructure(
                    ${'$'}pointer,
                    [type][KoaksWinCred+Credential]
                )
                if (${'$'}credential.CredentialBlobSize -eq 0) { exit 1 }
                ${'$'}bytes = New-Object byte[] ${'$'}credential.CredentialBlobSize
                [Runtime.InteropServices.Marshal]::Copy(${'$'}credential.CredentialBlob, ${'$'}bytes, 0, ${'$'}bytes.Length)
                [Console]::Out.Write([Text.Encoding]::Unicode.GetString(${'$'}bytes).TrimEnd([char]0))
            } finally {
                [KoaksWinCred]::CredFree(${'$'}pointer)
            }
            """.trimIndent()
        val result = BashCommandLine.execute(script, maxOutputChars = 8192, timeoutMillis = 15_000)
        return result.output.trim().takeIf { result.status == 0 && it.isNotEmpty() }
    }
}
