package org.koaks.agent.config

import org.koaks.agent.platform.Environment

public fun initializeConfig(
    env: Environment,
    force: Boolean = false,
): ConfigInitResult = ConfigFileLoader.initialize(env, force)
