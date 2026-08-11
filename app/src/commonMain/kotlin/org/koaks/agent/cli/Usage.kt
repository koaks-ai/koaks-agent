package org.koaks.agent.cli

public fun usageText(): String =
    """
    Usage:
      koaks [options]
      koaks init [--force]

    Options:
      -h, --help

    Diagnostics:
      KOAKS_TRACE_FILE=path   Write timing-only CLI lifecycle logs to path.

    Config:
      Default path: ${'$'}HOME/.koaks/config.toml

      schema_version = 1
      provider = "openai"
      show_reasoning = false
      skill_paths = [".agents/skills"]
      skills = ["code-review"]

      [providers.openai]
      base_url = "https://api.openai.com"
      credential_source = "environment"
      credential_name = "OPENAI_API_KEY"
      # Alternatively, replace the two credential fields with: api_key = "..."
      model = "gpt-5.5"
      model_list = ["gpt-5.5"]

      [providers.anthropic]
      base_url = "https://api.anthropic.com/v1/messages"
      credential_source = "environment"
      credential_name = "ANTHROPIC_API_KEY"
      # Alternatively, replace the two credential fields with: api_key = "..."
      model = "claude-opus-4-8"
      model_list = ["claude-opus-4-8"]

    Examples:
      koaks
      /provider anthropic
      /model claude-opus-4-8
      /reasoning on
    """.trimIndent()
