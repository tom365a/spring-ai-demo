package com.demo.cs.application.support;
import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.application.resources.*;
import com.demo.cs.domain.SupportAssignment;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
@Component
public class SimulatedSupportAdapter implements SupportImAdapter {
 private final DefinitionRegistry agents;private final ResourceService resources;private final ResourceModelFactory models;
 public SimulatedSupportAdapter(DefinitionRegistry agents,ResourceService resources,ResourceModelFactory models){this.agents=agents;this.resources=resources;this.models=models;}
 public String reply(SupportAssignment assignment,List<Message> history){
  var agent=agents.get(assignment.agentCode).orElseThrow(()->new IllegalStateException("模拟客服暂不可用"));
  var model=resources.published(agent.definition().modelConfig().resourceId(),"MODEL",null);
  var messages=new ArrayList<Message>();messages.add(new SystemMessage(agent.definition().prompts().systemPrompt()));messages.addAll(history);
  return models.create(model,resources.credential(model),Duration.ofSeconds(60)).call(new Prompt(messages)).getResult().getOutput().getText();
 }
}
