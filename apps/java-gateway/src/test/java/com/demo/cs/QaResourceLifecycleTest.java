package com.demo.cs;

import com.demo.cs.application.resources.*;
import com.demo.cs.infrastructure.persistence.*;
import com.demo.cs.infrastructure.resources.transport.McpToolTransport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Independent real-JPA resource slice. No application runners, .env, web server or model requests. */
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","APP_RESOURCE_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.resources.allowed-credential-env=QA_ONLY_SECRET","QA_ONLY_SECRET=qa-env-secret"})
@Import({ResourceService.class,CredentialVault.class,QaResourceLifecycleTest.Beans.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class QaResourceLifecycleTest {
 @TestConfiguration static class Beans {
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean McpToolTransport mcp(ObjectMapper mapper){return new McpToolTransport(mapper);}
 }
 @Autowired ResourceService resources;
 @Autowired CredentialVault vault;
 @Autowired ManagedResourceRepository repo;
 @Autowired ManagedResourceVersionRepository versions;
 @Autowired ResourceCredentialRepository credentials;
 @Autowired ObjectMapper json;
 String code(){return "qa_"+UUID.randomUUID().toString().replace("-","");}
 ObjectNode resource(String kind,ObjectNode config){var body=json.createObjectNode().put("kind",kind).put("code",code()).put("name","QA独立资源");body.set("config",config);return body;}
 ObjectNode schema(){return json.createObjectNode().put("type","object").set("properties",json.createObjectNode());}
 ObjectNode tool(String source,String effect){var c=json.createObjectNode().put("source",source).put("sideEffect",effect).put("requireConfirm",effect.equals("WRITE")).put("timeoutSeconds",3);c.set("inputSchema",schema());return c;}
 ObjectNode http(){var c=tool("HTTP","READ").put("url","http://127.0.0.1:18887/query").put("method","GET");c.putObject("target").put("scheme","http").put("host","127.0.0.1").put("port",18887).put("allowPrivate",true);c.putArray("mappings");c.putObject("auth").put("type","NONE");return c;}
 ObjectNode skill(String instruction,List<String> tools){var c=json.createObjectNode().put("instructions",instruction);c.set("applicableTypes",json.valueToTree(List.of("WORKER")));c.set("tools",json.valueToTree(tools));return c;}
 ObjectNode model(){var c=json.createObjectNode().put("provider","OPENAI").put("baseUrl","http://127.0.0.1:18887").put("model","qa-model");c.putObject("target").put("scheme","http").put("host","127.0.0.1").put("port",18887).put("allowPrivate",true);return c;}
 ObjectNode replace(String secret){return json.createObjectNode().put("action","REPLACE").put("source","INPUT").put("value",secret);}
 String save(ObjectNode body){return (String)resources.save(null,body,true).get("id");}
 Map<String,Object> publish(String id){return resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",resources.impact(id,"publish").get("token").toString()));}
 ObjectNode updateBody(String id,ObjectNode config){var r=resources.require(id);var b=resource(r.kind,config).put("code",r.code).put("draftRevision",r.draftRevision);return b;}

 @Test void callersCannotForgeBuiltinOrMcpToolIdentity(){
  for(String source:List.of("BUILTIN","MCP")){
   var c=tool(source,"READ").put("builtinCode","cancel_order").put("serviceId","not-real").put("nativeName","dangerous_write");
   assertThatThrownBy(()->resources.save(null,resource("TOOL",c),true)).as("untrusted create "+source).isInstanceOf(RuntimeException.class);
  }
 }
 @Test void builtinWriteRiskAndImplementationCannotBeDowngraded(){
  String code=code();var c=tool("BUILTIN","WRITE").put("builtinCode","cancel_order");resources.seed(code,"TOOL","取消订单","QA",c,null,true,false);
  String id=resources.require(code).id;var downgraded=c.deepCopy().put("sideEffect","READ").put("requireConfirm",false);
  assertThatThrownBy(()->resources.save(id,updateBody(id,downgraded),true)).isInstanceOf(RuntimeException.class);
  var changed=c.deepCopy().put("builtinCode","query_order");assertThatThrownBy(()->resources.save(id,updateBody(id,changed),true)).isInstanceOf(RuntimeException.class);
 }
 @Test void httpPathTemplateCanBeSavedWithoutChangingAuthorizedOrigin(){
  var c=http().put("url","http://127.0.0.1:18887/orders/{orderId}");c.withArray("mappings").addObject().put("sourceKind","INPUT").put("source","orderId").put("targetKind","PATH").put("target","orderId");
  assertThatCode(()->save(resource("TOOL",c))).doesNotThrowAnyException();
 }
 @Test void requiredCredentialsCannotBeAbsentWhenPublishing(){
  String model=save(resource("MODEL",model()));assertThatThrownBy(()->publish(model)).as("model requires credential").isInstanceOf(RuntimeException.class);
  var c=http();c.putObject("auth").put("type","BEARER");String http=save(resource("TOOL",c));assertThatThrownBy(()->publish(http)).as("Bearer requires credential").isInstanceOf(RuntimeException.class);
 }
 @Test void credentialsAreEncryptedAndOldPublishedCredentialSurvivesDraftClear()throws Exception{
  String secret="QA_RESOURCE_CLEAR_SECRET";var b=resource("MODEL",model());b.set("credentialChange",replace(secret));String id=save(b);publish(id);
  var published=resources.published(id,"MODEL",null);assertThat(resources.credential(published)).isEqualTo(secret);
  String raw=repo.findById(id).orElseThrow().draftJson;assertThat(raw).doesNotContain(secret);
  var stored=credentials.findById(published.credentialRef()).orElseThrow();assertThat(stored.getCiphertext()).startsWith("v1:").doesNotContain(secret);
  var clear=updateBody(id,model());clear.set("credentialChange",json.createObjectNode().put("action","CLEAR"));resources.save(id,clear,true);
  assertThat(resources.credential(resources.published(id,"MODEL",null))).isEqualTo(secret);
  assertThatThrownBy(()->publish(id)).isInstanceOf(RuntimeException.class);
 }
 @Test void viewsAndAuditNeverExposePlaintextOrCredentialReference()throws Exception{
  String secret="QA_RESOURCE_NOT_FOR_BROWSER";var b=resource("MODEL",model());b.set("credentialChange",replace(secret));String id=save(b);publish(id);
  String ref=repo.findById(id).orElseThrow().credentialRef;
  for(Object view:List.of(resources.detail(id),resources.list(null,null,null,null),resources.impact(id,"publish"),resources.history(id),resources.auditHistory(id)))assertThat(json.writeValueAsString(view)).doesNotContain(secret,ref,"ciphertext");
 }
 @Test void pinnedSkillSnapshotStaysStableAcrossPublishAndReferencedToolCannotBeDeleted(){
  String tool=save(resource("TOOL",http()));publish(tool);String toolCode=resources.require(tool).code;
  String skill=save(resource("SKILL",skill("VERSION_ONE",List.of(toolCode))));publish(skill);
  assertThatThrownBy(()->resources.delete(tool)).isInstanceOf(IllegalStateException.class);
  resources.save(skill,updateBody(skill,skill("VERSION_TWO",List.of())),true);publish(skill);
  assertThat(resources.published(skill,"SKILL",1).config().path("instructions").asText()).isEqualTo("VERSION_ONE");assertThat(resources.published(skill,"SKILL",null).version()).isEqualTo(2);
  resources.delete(tool); // Only history refers after current draft/published skill dependency removal.
  assertThatThrownBy(()->resources.lifecycle(skill,"rollback",json.createObjectNode().put("version",1).put("impactToken",resources.impact(skill,"rollback",1).get("token").toString()))).isInstanceOf(RuntimeException.class);
 }
 @Test void impactIsInvalidatedByEditAndConcurrentPublishConsumesOnlyOneToken()throws Exception{
  String id=save(resource("TOOL",http()));String old=resources.impact(id,"publish").get("token").toString();resources.save(id,updateBody(id,http().put("responsePath","$")),true);
  assertThatThrownBy(()->resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",old))).isInstanceOf(IllegalStateException.class);
  String token=resources.impact(id,"publish").get("token").toString();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  Callable<Boolean> action=()->{start.await();try{resources.lifecycle(id,"publish",json.createObjectNode().put("impactToken",token));return true;}catch(IllegalStateException e){return false;}};
  try{var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);assertThat(resources.require(id).publishedVersion).isEqualTo(1);}finally{pool.shutdownNow();}
 }
 @Test void mcpSyncCandidateRequiresPublicationAndSameNativeNamesRemainSeparate()throws Exception {
  var f=new QaResourceTransportTest();var include=new java.util.concurrent.atomic.AtomicBoolean(true);
  try(var server=f.new Fixture(x->{if(x.getRequestMethod().equals("DELETE")){x.sendResponseHeaders(204,-1);return;}var req=json.readTree(x.getRequestBody());if(f.handshake(x,req))return;f.rpc(x,req,Map.of("tools",include.get()?List.of(Map.of("name","same_name","inputSchema",Map.of("type","object","properties",Map.of()))):List.of()));})){
   String first=save(resource("MCP",server.config())),second=save(resource("MCP",server.config()));resources.sync(first);resources.sync(second);
   assertThat(resources.list("TOOL",null,null,"MCP").stream().filter(t->((JsonNode)t.get("config")).path("serviceId").asText().equals(first))).isEmpty();
   publish(first);publish(second);var tools=resources.list("TOOL",null,null,"MCP").stream().filter(t->Set.of(first,second).contains(((JsonNode)t.get("config")).path("serviceId").asText())).toList();
   assertThat(tools).hasSize(2);assertThat(tools.get(0).get("code")).isNotEqualTo(tools.get(1).get("code"));
   String toolId=tools.stream().filter(t->((JsonNode)t.get("config")).path("serviceId").asText().equals(first)).findFirst().orElseThrow().get("id").toString();
   include.set(false);resources.sync(first);assertThat(resources.published(toolId,"TOOL",null)).isNotNull();publish(first);assertThatThrownBy(()->resources.published(toolId,"TOOL",null)).isInstanceOf(IllegalStateException.class);
  }
 }
 @Test void mcpToolSchemaCannotBeEditedOutsideServiceDiscovery() {
  var config=tool("MCP","WRITE").put("serviceId","qa-discovered-service").put("nativeName","qa_native");String code=code();resources.seed(code,"TOOL","Discovered tool","QA",config,null,true,false);String id=resources.require(code).id;
  var altered=config.deepCopy();altered.set("inputSchema",json.createObjectNode().put("type","object").set("properties",json.createObjectNode().set("forged",json.createObjectNode().put("type","string"))));
  assertThatThrownBy(()->resources.save(id,updateBody(id,altered),true)).as("MCP schema belongs to synchronized service directory").isInstanceOf(RuntimeException.class);
 }
 @Test void rollbackPreviewShowsSelectedHistoryAndCannotBeReusedForAnotherVersion() {
  String id=save(resource("SKILL",skill("ROLLBACK_ONE",List.of())));publish(id);resources.save(id,updateBody(id,skill("ROLLBACK_TWO",List.of())),true);publish(id);
  resources.save(id,updateBody(id,skill("UNPUBLISHED_THREE",List.of())),true);Map<String,Object> impact=resources.impact(id,"rollback",1);
  JsonNode after=json.valueToTree(impact.get("changes")).path("after");assertThat(after.path("config").path("instructions").asText()).isEqualTo("ROLLBACK_ONE");
  String token=impact.get("token").toString();assertThatThrownBy(()->resources.lifecycle(id,"rollback",json.createObjectNode().put("version",2).put("impactToken",token))).isInstanceOf(IllegalStateException.class);
  resources.lifecycle(id,"rollback",json.createObjectNode().put("version",1).put("impactToken",token));assertThat(resources.published(id,"SKILL",null).config().path("instructions").asText()).isEqualTo("ROLLBACK_ONE");assertThat(resources.require(id).publishedVersion).isEqualTo(3);
 }
}
