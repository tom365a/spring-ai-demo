package com.demo.cs.agent.runtime;

import com.demo.cs.application.agentconfig.model.AgentDefinition;

public record PublishedAgent(String code, int version, AgentDefinition definition) {}
