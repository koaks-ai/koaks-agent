# koaks-agent

A Kotlin/Native terminal agent built as a thin product layer on top of Koaks.

The product is split into three Gradle modules: `app-cli` for composition and runtime
lifecycle, `agent-core` for configuration/providers/tools/platform integration, and
`frontend-tui` for terminal interaction and rendering.

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
credential_source = "environment"
credential_name = "OPENAI_API_KEY"
model = "gpt-5.5"
model_list = ["gpt-5.5"]
```

`credential_source` is either `environment` or `system`. The latter reads Windows
Credential Manager or macOS Keychain. For local-only use, `api_key` is also accepted;
it is never shown by `/status`, but plaintext keys in config should be protected with
appropriate filesystem permissions.

## Build

Java 21 is required for Gradle.

```powershell
.\gradlew.bat :app-cli:windowsX64Test :app-cli:linkReleaseExecutableWindowsX64
```

```bash
./gradlew :app-cli:macosArm64Test :app-cli:linkReleaseExecutableMacosArm64
```

See [architecture](docs/architecture.md) for module responsibilities and
[Koaks integration gaps](docs/koaks-gaps.md) for the current macOS x64 limitation.
