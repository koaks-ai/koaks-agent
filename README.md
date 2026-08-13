# koaks-agent

A Kotlin Multiplatform terminal agent built as a thin product layer on top of Koaks.

The product is split into three Gradle modules: `app` for process lifecycle and the
composition root, `agent` for product configuration/session/tools/provider logic, and
`tui` for terminal interaction and rendering.

## Configure

Initialization is explicit; normal startup never creates or rewrites configuration:

```text
koaks init
koaks init --force
```

The default file is `~/.koaks/config.toml`. `--force` creates a timestamped backup
before atomically replacing it.

```toml
schema_version = 1
provider = "openai"
show_reasoning = false

[providers.openai]
base_url = "https://api.openai.com"
api_key = "your-openai-api-key"
model = "gpt-5.5"
model_list = ["gpt-5.5"]
```

Only an explicit `api_key` in the configuration file is supported. Legacy
`credential_source` and `credential_name` fields fail with a migration message.
The key is never shown by `/status`; protect the plaintext configuration file with
appropriate filesystem permissions.

## Build

Java 21 is required for Gradle.

```powershell
.\gradlew.bat :agent:jvmTest :tui:jvmTest :app:jvmTest :app:jvmJar
.\gradlew.bat :app:runJvm -PappArgs=--help
```

Build and run the self-contained JVM jar (Java 21 is still required). Use the
packaged jar for interactive TUI sessions so JLine is attached directly to the
terminal and can receive raw keyboard and mouse events:

```powershell
.\gradlew.bat :app:jvmFatJar
java -jar app\build\libs\koaks-agent.jar
```

Gradle's `runJvm` task is intended for argument and startup smoke tests such as
`--help`; Gradle forwards standard input through a pipe, so it cannot expose the
Windows console's raw arrow-key and mouse-wheel events to JLine.

Native validation on Windows:

```powershell
.\gradlew.bat :agent:windowsX64Test :tui:windowsX64Test :app:windowsX64Test :app:linkReleaseExecutableWindowsX64
```

```bash
./gradlew :agent:macosArm64Test :tui:macosArm64Test :app:macosArm64Test :app:linkReleaseExecutableMacosArm64
```

See [architecture](docs/architecture.md) for module responsibilities and
[Koaks integration gaps](docs/koaks-gaps.md) for the current macOS x64 limitation.
