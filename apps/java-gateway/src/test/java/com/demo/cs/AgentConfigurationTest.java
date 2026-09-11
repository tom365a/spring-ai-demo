package com.demo.cs;

import com.demo.cs.agent.runtime.*;
import com.demo.cs.agent.model.AgentModels.*;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.api.dto.ApiDtos;
import com.demo.cs.application.agentconfig.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.application.knowledge.KnowledgeService;
import com.demo.cs.application.orchestrator.AgentOrchestrator;
import com.demo.cs.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:architect;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "app.agent-config.seed-on-startup=false","app.upload-dir=./target/self-test-uploads","app.knowledge-sample-dir=./target/no-samples","OPENAI_API_KEY=synthetic-legacy-regression-only"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AgentConfigurationTest {
    @Autowired AgentDefinitionService definitions;
    @Autowired AgentPublishService publisher;
    @Autowired DefinitionRegistry registry;
    @Autowired AgentDefinitionValidator validator;
    @Autowired ConfigurableAgentInvoker invoker;
    @Autowired AgentOrchestrator orchestrator;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean ChatModel model;
    @MockitoBean com.demo.cs.application.resources.ResourceModelFactory resourceModels;
    @MockitoBean KnowledgeService knowledge;
    @BeforeEach void setup() {
        reset(model,knowledge);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(answer("test answer"));
        when(resourceModels.create(any(),anyString(),any())).thenReturn(model);
        when(knowledge.retrieveDocuments(anyString(),anyInt())).thenReturn(List.of(new Document("verified knowledge")));
        when(knowledge.toCitations(anyList())).thenReturn(List.of());
    }
    ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    String code() { return "t_"+UUID.randomUUID().toString().replace("-","").substring(0,10); }
    AgentDefinition def(String code,String prompt,List<String> tools,List<String> skills) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(Map.of("code",code,"name","测试Agent","type","WORKER",
                "description","测试简介","prompts",Map.of("systemPrompt",prompt,"userPromptTemplate","{{text}}"),"tools",tools,"skills",skills)),AgentDefinition.class);
    }
    AgentDetailResponse create(AgentDefinition def) {
        return definitions.create(new CreateAgentRequest(def.code(),def.name(),def.type(),def.description(),def),"test");
    }
    SubAgentRequest request(String code) { return new SubAgentRequest("self-test","u_001","查订单",null,List.of(),List.of(),
        new RouteDecision("test",1,code,"test",new Slots(null,null,null,null),false,null),false); }

    @Test void lifecycleKeepsDraftSeparateAndRestoresPublishedSnapshot() throws Exception {
        String code=code(); create(def(code,"version one",List.of(),List.of("clear_response")));
        assertThat(definitions.get(code).enabled()).isFalse(); assertThat(registry.get(code)).isEmpty();
        assertThat(publisher.enable(code,"test").publishedVersion()).isEqualTo(1);
        var detail=definitions.get(code);
        definitions.update(code,new UpdateAgentRequest(null,null,null,null,detail.draftRevision(),def(code,"version two",List.of(),List.of()),null,null),"test");
        assertThat(registry.get(code).orElseThrow().definition().prompts().systemPrompt()).isEqualTo("version one");
        assertThat(publisher.publish(code,new PublishRequest("update"),"test").publishedVersion()).isEqualTo(2);
        publisher.rollback(code,new RollbackRequest(1,"restore"),"test");
        assertThat(registry.get(code).orElseThrow().definition().skills()).containsExactly("clear_response");
        publisher.disable(code,"test"); assertThat(registry.get(code)).isEmpty();
        publisher.enable(code,"test"); registry.clearAndLoad(List.of()); publisher.reloadRegistryFromDb();
        assertThat(registry.get(code).orElseThrow().version()).isEqualTo(3);
    }
    @Test void skillsAndToolsReachRealChatClientPromptAndRemovalStopsThem() throws Exception {
        String code=code();
        var definition=def(code,"custom system",List.of("query_order"),List.of("clear_response","order_lookup","knowledge_answer"));
        invoker.handle(definition,1,request(code));
        var capture=ArgumentCaptor.forClass(Prompt.class); verify(model).call(capture.capture());
        var prompt=capture.getValue();
        assertThat(prompt.getSystemMessage().getText()).contains("custom system","清晰表达","订单查询","知识检索问答");
        assertThat(prompt.getUserMessage().getText()).contains("verified knowledge");
        assertThat(((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks()).extracting(t->t.getToolDefinition().name()).containsExactlyInAnyOrder("query_order","query_logistics");
        verify(knowledge).retrieveDocuments("查订单",5);
        reset(model,knowledge); when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build()); when(model.call(any(Prompt.class))).thenReturn(answer("ok"));
        invoker.handle(def(code,"custom system",List.of(),List.of()),2,request(code));
        var removed=ArgumentCaptor.forClass(Prompt.class); verify(model).call(removed.capture());
        assertThat(removed.getValue().getSystemMessage().getText()).doesNotContain("清晰表达","订单查询");
        verifyNoInteractions(knowledge);
        var options=(ToolCallingChatOptions)removed.getValue().getOptions();
        assertThat(options==null || options.getToolCallbacks()==null || options.getToolCallbacks().isEmpty()).isTrue();
    }
    @Test void arbitraryMainRoutesToConfiguredWorkerAndDisabledMainDegradesToHandoff() throws Exception {
        String worker=code(), main=code(); create(def(worker,"worker custom",List.of(),List.of())); publisher.enable(worker,"test");
        var parent=mapper.readValue(mapper.writeValueAsString(Map.of("code",main,"name","主测试","type","SUPERVISOR",
          "prompts",Map.of("systemPrompt","main custom"),"children",List.of(Map.of("agentCode",worker)))),AgentDefinition.class);
        create(parent); publisher.enable(main,"test");
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt p=call.getArgument(0);
            return answer(p.getSystemMessage().getText().contains("main custom")?
                "{\"intent\":\"custom\",\"confidence\":0.99,\"targetAgent\":\""+worker+"\",\"slots\":{},\"needClarify\":false}":"worker answer");
        });
        var request=new ApiDtos.ChatRequest(null,"u_001","hello custom",List.of(),null,null,"zh-CN",null,main);
        assertThat(orchestrator.chat(request).agentName()).isEqualTo(worker);
        publisher.disable(worker,"test");
        assertThat(orchestrator.chat(request).answer()).contains("没有可用子Agent");
        publisher.disable(main,"test");
        // 异常不得抛到前台：主 Agent 停用后降级为可转人工的兜底回复，而不是抛出。
        var degraded=orchestrator.chat(request);
        assertThat(degraded.answer()).contains("转人工");
        assertThat(degraded.agentName()).isEqualTo("supervisor");
        assertThat(degraded.confirmRequired()).isFalse();
        assertThat(degraded.diagnostics()).containsEntry("handoffAvailable",true);
    }
    @Test void serverRejectsInvalidRelationshipsAndCapabilities() throws Exception {
        var worker=def(code(),"ok",List.of(),List.of());
        var tree=mapper.valueToTree(worker); ((com.fasterxml.jackson.databind.node.ObjectNode)tree).putArray("children").addObject().put("agentCode","missing");
        assertThat(validator.validate(mapper.treeToValue(tree,AgentDefinition.class)).ok()).isFalse();
        assertThat(validator.validate(def(code(),"ok",List.of("cancel_order"),List.of())).ok()).isFalse();
        assertThat(validator.validate(def(code(),"ok",List.of(),List.of("unknown"))).ok()).isFalse();
        assertThat(validator.validate(def(code(),"{{unknown_var}}",List.of(),List.of())).ok()).isFalse();
    }
    @Test void concurrentDraftRevisionRejectsOneWriter() throws Exception {
        String code=code(); var detail=create(def(code,"initial",List.of(),List.of()));
        var update=new UpdateAgentRequest(null,null,null,null,detail.draftRevision(),def(code,"changed",List.of(),List.of()),null,null);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            Callable<Boolean> work=()->{start.await();try { definitions.update(code,update,"test");return true; }catch(IllegalStateException conflict){return false;}};
            var a=executor.submit(work); var b=executor.submit(work); start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentEnableIsIdempotent() throws Exception {
        String code=code();create(def(code,"ready",List.of(),List.of()));
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->publisher.enable(code,"test"));
            var b=executor.submit(()->publisher.enable(code,"test"));
            assertThat(a.get().publishedVersion()).isEqualTo(1); assertThat(b.get().publishedVersion()).isEqualTo(1);
            assertThat(registry.get(code).orElseThrow().version()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void apiPermissionsAutoCodeAndMalformedInput() throws Exception {
        String body="{\"name\":\"页面测试\",\"definition\":{\"name\":\"页面测试\",\"prompts\":{\"systemPrompt\":\"hello\"}}}";
        mvc.perform(post("/api/v1/admin/agents").header("X-Admin-Role","viewer").contentType("application/json").content(body)).andExpect(status().isForbidden());
        var created=mvc.perform(post("/api/v1/admin/agents").header("X-Admin-Role","editor").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        var data=mapper.readTree(created.getResponse().getContentAsString()).path("data");
        assertThat(data.path("enabled").asBoolean()).isFalse(); String code=data.path("draft").path("code").asText();
        assertThat(code).startsWith("agent_");
        mvc.perform(post("/api/v1/admin/agents/"+code+"/enable").header("X-Admin-Role","editor")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/agents/"+code+"/enable").header("X-Admin-Role","publisher")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/agents").header("X-Admin-Role","editor").contentType("application/json").content("{bad")).andExpect(status().isBadRequest());
    }
}
