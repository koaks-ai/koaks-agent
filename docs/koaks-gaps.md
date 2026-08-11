# Koaks integration gaps

## macOS x64 artifacts

Koaks Core/provider artifacts referenced by this project are not currently published
for `macosX64`. The target is defined by the shared convention plugin but is disabled
by default with:

```properties
koaksEnableMacosX64=false
```

Minimal reproduction after publishing is available from this repository:

```bash
./gradlew -PkoaksEnableMacosX64=true :app-cli:linkReleaseExecutableMacosX64
```

CI keeps this job visible as non-blocking. Once matching Koaks artifacts exist, remove
`continue-on-error`, set the property to `true`, and make macOS x64 a normal release
gate. No runtime or provider implementation is copied into `koaks-agent` as a
workaround.

## Process isolation is outside Koaks runtime semantics

Koaks resource scopes provide structured lifetime and cancellation to the tool call,
but they do not create an operating-system sandbox. The `agent-core` tool package
therefore labels shell execution as a side effect, requests human approval, limits
its environment, deadline and output, and makes no sandbox claim. Stronger
process-tree or filesystem isolation would require a platform sandbox implementation
rather than a duplicate Koaks runtime abstraction.
