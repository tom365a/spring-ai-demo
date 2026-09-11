package com.demo.cs.application.resources;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentResourceResolver {
 private final ResourceService resources;
 public AgentResourceResolver(ResourceService resources){this.resources=resources;}
 public record Resolved(ResourceSnapshot model,List<ResourceSnapshot> skills,Map<String,ResourceSnapshot> tools,Map<String,ResourceSnapshot> services) {}
 public Resolved resolve(AgentDefinition def) {
 ResourceSnapshot model=resources.published(def.modelConfig().resourceId(),"MODEL",null);
 List<ResourceSnapshot> skills=new ArrayList<>();Set<String> codes=new LinkedHashSet<>(def.tools());codes.addAll(def.mcp().toolAllowlist());
 for(String code:def.skills()) {
 Integer pinned=def.skillVersions().get(code);if(pinned==null)pinned=1; // migrated trusted v1 bindings remain pinned
 ResourceSnapshot s=resources.published(code,"SKILL",pinned);boolean applicable=false;
 for(JsonNode type:s.config().path("applicableTypes"))if(type.asText().equals(def.type()))applicable=true;
 if(!applicable)throw new IllegalStateException("skills."+code+": 不适用于当前Agent类型");
 skills.add(s);s.config().path("tools").forEach(t->codes.add(t.asText()));
 }
 Map<String,ResourceSnapshot> tools=new LinkedHashMap<>(),services=new LinkedHashMap<>();
 for(String id:def.mcp().serverIds()){var s=resources.published(id,"MCP",null);services.put(s.id(),s);}
 for(String code:codes) {
 ResourceSnapshot t=resources.published(code,"TOOL",null);
 if(t.config().path("sideEffect").asText().equals("WRITE")&&(!def.type().equals("WORKER")||!def.policies().writeToolsAllowed()))throw new IllegalStateException("tools."+code+": 未授权写工具");
 tools.put(code,t);
 if(t.config().path("source").asText().equals("MCP")) {var service=resources.published(t.config().path("serviceId").asText(),"MCP",null);services.put(service.id(),service);}
 }
 if(!tools.isEmpty()&&!model.config().path("capabilities").path("tools").asBoolean(true))throw new IllegalStateException("modelConfig.resourceId: 模型未声明工具能力");
 for(String code:def.policies().requireConfirmFor())if(code==null||!tools.containsKey(code))throw new IllegalStateException("policies.requireConfirmFor: 只能选择已授权工具");
 return new Resolved(model,List.copyOf(skills),Collections.unmodifiableMap(tools),Collections.unmodifiableMap(services));
 }
 public void available(Resolved resolved,boolean sameVersion) {
 resources.available(resolved.model(),sameVersion);resolved.skills().forEach(s->resources.available(s,sameVersion));resolved.tools().values().forEach(s->resources.available(s,sameVersion));resolved.services().values().forEach(s->resources.available(s,sameVersion));
 }
 public String instructions(Resolved r) {StringBuilder b=new StringBuilder();for(var s:r.skills())b.append("\n【技能 ").append(s.name()).append(" v").append(s.version()).append("】\n").append(s.config().path("instructions").asText());return b.toString();}
 public boolean rag(AgentDefinition def,Resolved r) {return def.capabilities().ragEnabled()||r.skills().stream().anyMatch(s->s.config().path("enableRag").asBoolean());}
}
