package com.demo.cs.agent;

import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;

public interface SubAgent {
    String name();
    SubAgentResult handle(SubAgentRequest request);
}
