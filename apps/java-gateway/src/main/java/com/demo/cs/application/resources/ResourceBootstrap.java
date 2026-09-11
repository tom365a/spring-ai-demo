package com.demo.cs.application.resources;
import com.demo.cs.agent.runtime.ToolBindingFactory;
import com.demo.cs.infrastructure.catalog.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component @Order(10)
public class ResourceBootstrap implements ApplicationRunner {
 private final ResourceService resources; private final LocalToolCatalog tools;private final LocalSkillCatalog skills;private final ToolBindingFactory bindings;private final ObjectMapper json;private final Environment env;
 public ResourceBootstrap(ResourceService resources,LocalToolCatalog tools,LocalSkillCatalog skills,ToolBindingFactory bindings,ObjectMapper json,Environment env){this.resources=resources;this.tools=tools;this.skills=skills;this.bindings=bindings;this.json=json;this.env=env;}
 public void run(ApplicationArguments ignored)throws Exception {
 for(var t:tools.list()){ObjectNode c=json.createObjectNode().put("source","BUILTIN").put("builtinCode",t.code()).put("sideEffect",t.sideEffect().name()).put("requireConfirm",t.sideEffect()==LocalToolCatalog.SideEffect.WRITE).put("timeoutSeconds",30);
 c.set("inputSchema",json.readTree(bindings.resolve(java.util.List.of(t.code()))[0].getToolDefinition().inputSchema()));
 // 二选一工具：把按钮文案和「拒绝」分支要执行的工具带进配置，运行时据此渲染确认卡片。
 if(t.choice()!=null){ObjectNode ch=c.putObject("choice");ch.put("prompt",t.choice().prompt()).put("confirmLabel",t.choice().confirmLabel()).put("cancelLabel",t.choice().cancelLabel()).put("rejectTool",t.choice().rejectTool());}
 resources.seed(t.code(),"TOOL",t.name(),t.description(),c,null,true,false);
 // 已存在的行 seed 不会碰，靠对账把代码目录的变更同步下去。
 resources.reconcileBuiltinTool(t.code(),t.name(),t.description(),c);}
 for(var s:skills.list()){ObjectNode c=json.createObjectNode().put("instructions",s.instructions()).put("enableRag",s.enableRag()).put("usageNotes","");
 c.set("applicableTypes",json.valueToTree(s.applicableTypes()));c.set("tools",json.valueToTree(s.tools()));resources.seed(s.code(),"SKILL",s.name(),s.description(),c,null,true,false);}
 boolean kimi=env.getProperty("app.llm.provider","openai").equalsIgnoreCase("kimi");
 model("model_kimi","KIMI",env.getProperty("app.llm.kimi.base-url","https://api.moonshot.cn"),env.getProperty("app.llm.kimi.model","kimi-k3"),"KIMI_API_KEY",kimi);
 model("model_openai","OPENAI",env.getProperty("spring.ai.openai.base-url","https://api.openai.com"),env.getProperty("spring.ai.openai.chat.options.model","gpt-4o-mini"),"OPENAI_API_KEY",!kimi);
 }
 private void model(String code,String provider,String base,String model,String key,boolean active) {
 URI u=URI.create(base);ObjectNode c=json.createObjectNode().put("provider",provider).put("baseUrl",base).put("model",model).put("timeoutSeconds",120);
 c.putObject("target").put("scheme",u.getScheme()).put("host",u.getHost()).put("port",u.getPort()<0?(u.getScheme().equals("https")?443:80):u.getPort()).put("allowPrivate",false);
 boolean vision=provider.equals("KIMI")&&(model.equals("kimi-k3")||model.equals("kimi-k2.5"))||provider.equals("OPENAI")&&(model.startsWith("gpt-4o")||model.startsWith("gpt-4.1"));
 c.putObject("capabilities").put("chat",true).put("tools",true).put("vision",vision);
 if(provider.equals("KIMI"))c.putObject("parameters").put("reasoningEffort","low").put("maxCompletionTokens",4096);else c.putObject("parameters").put("temperature",0.2).put("maxTokens",2048);
 resources.seed(code,"MODEL",provider+" 默认模型","从环境配置迁移；凭证仅保留环境变量引用",c,key,active,active);
 }
}
