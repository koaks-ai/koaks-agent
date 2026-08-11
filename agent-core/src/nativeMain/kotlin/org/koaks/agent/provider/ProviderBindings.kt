package org.koaks.agent.provider

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.CliException
import org.koaks.agent.config.Provider
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.provider.anthropic.anthropic
import org.koaks.provider.ollama.ollama
import org.koaks.provider.openai.openai
import org.koaks.provider.qwen.qwen

public interface ProviderSettings

public interface ProviderBinding<C : ProviderSettings> {
    public val provider: Provider

    public fun settings(config: AgentConfig): C

    public fun ModelScope.select(
        settings: C,
        credential: String,
    ): ModelSelection
}

public class ProviderRegistry private constructor(
    private val selectors: Map<Provider, (ModelScope, AgentConfig, String) -> ModelSelection>,
) {
    internal fun select(
        scope: ModelScope,
        config: AgentConfig,
        credential: String,
    ): ModelSelection {
        val selector =
            selectors[config.provider]
                ?: throw CliException("No ProviderBinding registered for ${config.provider.id}.")
        return selector(scope, config, credential)
    }

    public class Builder public constructor() {
        private val selectors = linkedMapOf<Provider, (ModelScope, AgentConfig, String) -> ModelSelection>()

        public fun <C : ProviderSettings> register(binding: ProviderBinding<C>): Builder =
            apply {
                check(binding.provider !in selectors) { "Duplicate ProviderBinding: ${binding.provider.id}" }
                selectors[binding.provider] = { scope, config, credential ->
                    val settings = binding.settings(config)
                    with(binding) { scope.select(settings, credential) }
                }
            }

        public fun build(): ProviderRegistry = ProviderRegistry(selectors.toMap())
    }

    public companion object {
        public fun defaults(): ProviderRegistry =
            Builder()
                .register(OpenAiBinding)
                .register(QwenBinding)
                .register(AnthropicBinding)
                .register(OllamaBinding)
                .build()
    }
}

private data class OpenAiSettings(
    val baseUrl: String,
    val model: String,
    val temperature: Double?,
) : ProviderSettings

private data class QwenSettings(
    val baseUrl: String,
    val model: String,
    val temperature: Double?,
    val reasoning: Boolean,
) : ProviderSettings

private data class AnthropicSettings(
    val baseUrl: String,
    val model: String,
    val temperature: Double?,
    val reasoning: Boolean,
) : ProviderSettings

private data class OllamaSettings(
    val baseUrl: String,
    val model: String,
    val temperature: Double?,
    val reasoning: Boolean,
) : ProviderSettings

private object OpenAiBinding : ProviderBinding<OpenAiSettings> {
    override val provider: Provider = Provider.OPENAI

    override fun settings(config: AgentConfig): OpenAiSettings = OpenAiSettings(config.baseUrl, config.modelName, config.temperature)

    override fun ModelScope.select(
        settings: OpenAiSettings,
        credential: String,
    ): ModelSelection = openai(settings.baseUrl, credential, settings.model) { temperature = settings.temperature }
}

private object QwenBinding : ProviderBinding<QwenSettings> {
    override val provider: Provider = Provider.QWEN

    override fun settings(config: AgentConfig): QwenSettings =
        QwenSettings(config.baseUrl, config.modelName, config.temperature, config.showReasoning)

    override fun ModelScope.select(
        settings: QwenSettings,
        credential: String,
    ): ModelSelection =
        qwen(settings.baseUrl, credential, settings.model) {
            temperature = settings.temperature
            enableThinking = settings.reasoning
        }
}

private object AnthropicBinding : ProviderBinding<AnthropicSettings> {
    override val provider: Provider = Provider.ANTHROPIC

    override fun settings(config: AgentConfig): AnthropicSettings =
        AnthropicSettings(config.baseUrl, config.modelName, config.temperature, config.showReasoning)

    override fun ModelScope.select(
        settings: AnthropicSettings,
        credential: String,
    ): ModelSelection =
        anthropic(settings.baseUrl, credential, settings.model) {
            temperature = settings.temperature
            if (settings.reasoning) {
                thinking =
                    buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", 1024)
                    }
            }
        }
}

private object OllamaBinding : ProviderBinding<OllamaSettings> {
    override val provider: Provider = Provider.OLLAMA

    override fun settings(config: AgentConfig): OllamaSettings =
        OllamaSettings(config.baseUrl, config.modelName, config.temperature, config.showReasoning)

    override fun ModelScope.select(
        settings: OllamaSettings,
        credential: String,
    ): ModelSelection =
        ollama(settings.baseUrl, credential, settings.model) {
            temperature = settings.temperature
            think = settings.reasoning
        }
}
