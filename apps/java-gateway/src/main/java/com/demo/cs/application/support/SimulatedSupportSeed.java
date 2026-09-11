package com.demo.cs.application.support;
import com.demo.cs.application.agentconfig.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.infrastructure.persistence.AgtAgentRepository;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @Order(30)
public class SimulatedSupportSeed implements ApplicationRunner {
 private final AgtAgentRepository repo;private final AgentDefinitionService definitions;private final AgentPublishService publish;
 public SimulatedSupportSeed(AgtAgentRepository repo,AgentDefinitionService definitions,AgentPublishService publish){this.repo=repo;this.definitions=definitions;this.publish=publish;}
 public void run(ApplicationArguments ignored){for(String code:HumanSupportService.CODES){if(repo.findByCode(code).isPresent())continue;
  var def=new AgentDefinition(code,"模拟人工客服 "+code.substring(code.length()-1),"模拟客服IM坐席，由用户点击转人工后接管", "WORKER",null,new AgentDefinition.Prompts("你是模拟人工客服，必须明确自己是AI模拟坐席，不是真人。阅读本会话公开历史继续协助用户，必要时收集缺失信息。没有工具执行权限，不得声称已经退款、下单、修复bug或处理真实业务。不知道时明确说明。保持简洁友善。","{{text}}","TEXT"),null,List.of(),List.of(),null,null,List.of(),null,new AgentDefinition.Policies(false,List.of(),0,0),null,null);
  definitions.create(new CreateAgentRequest(code,def.name(),"WORKER",def.description(),def),"support-seed");publish.publish(code,new PublishRequest("模拟IM坐席初始化"),"support-seed");
 }}
}
