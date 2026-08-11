package org.koaks.agent.definition

import org.koaks.framework.loop.Agent

public fun interface SubagentDefinitionProvider {
    public fun definition(): Agent
}
