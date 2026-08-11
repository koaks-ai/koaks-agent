package org.koaks.agent.config

import org.koaks.agent.platform.Environment

public fun ConfigResolver.resolve(env: Environment): AgentConfig = resolve(ConfigFileLoader.load(env))

public fun initializeConfig(
    env: Environment,
    force: Boolean = false,
): ConfigInitResult = ConfigFileLoader.initialize(env, force)
