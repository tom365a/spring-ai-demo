package com.demo.cs.application.agentconfig;

import com.demo.cs.application.agentconfig.AgentValidationException.ValidationIssue;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.config.AppProperties;
import com.demo.cs.infrastructure.catalog.LocalToolCatalog;
import com.demo.cs.infrastructure.catalog.LocalSkillCatalog;
import com.demo.cs.infrastructure.persistence.AgtAgentRepository;
import com.demo.cs.agent.runtime.PromptTemplateRenderer;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentDefinitionValidator {
    private final LocalToolCatalog tools;
    private final LocalSkillCatalog skills;
    private final AgtAgentRepository agents;
    private final PromptTemplateRenderer renderer;
    private final AppProperties props;
    private final com.demo.cs.application.resources.ResourceService resources;
    private final com.demo.cs.application.resources.AgentResourceResolver resourceResolver;
    public AgentDefinitionValidator(LocalToolCatalog tools, LocalSkillCatalog skills, AgtAgentRepository agents,
                                    PromptTemplateRenderer renderer, AppProperties props,
                                    com.demo.cs.application.resources.ResourceService resources,
                                    com.demo.cs.application.resources.AgentResourceResolver resourceResolver) {
        this.tools=tools; this.skills=skills; this.agents=agents; this.renderer=renderer; this.props=props;
        this.resources=resources;this.resourceResolver=resourceResolver;
    }
    public ValidationResult validate(AgentDefinition def) { return validate(def, true); }
    public ValidationResult validateDraft(AgentDefinition def) { return validate(def, false); }
    private ValidationResult validate(AgentDefinition def, boolean activating) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        if (def == null) return new ValidationResult(false, List.of(new ValidationIssue("definition", "definition is required")), warnings);
        if (def.code()==null || !def.code().matches("^[a-z][a-z0-9_]{1,63}$")) issue(errors,"code","标识需为2–64位小写字母、数字或下划线，且以字母开头");
        if (def.name()==null || def.name().isBlank() || def.name().length()>128) issue(errors,"name","名称必填，最多128字符");
        if (def.description()!=null && def.description().length()>1000) issue(errors,"description","简介最多1000字符");
        if (!List.of("SUPERVISOR","WORKER").contains(Objects.toString(def.type(), ""))) issue(errors,"type","类型必须为SUPERVISOR或WORKER");
        String system = def.prompts().systemPrompt();
        if (system.isBlank() || system.length()>props.agentConfig().promptMaxLength()) issue(errors,"prompts.systemPrompt","提示词必填且不能超过"+props.agentConfig().promptMaxLength()+"字符");
        if (def.prompts().userPromptTemplate().length()>props.agentConfig().promptMaxLength()) issue(errors,"prompts.userPromptTemplate","用户模板过长");
        renderer.findUnknownPlaceholders(system).forEach(p -> issue(errors,"prompts.systemPrompt","未知变量: "+p));
        renderer.findUnknownPlaceholders(def.prompts().userPromptTemplate()).forEach(p -> issue(errors,"prompts.userPromptTemplate","未知变量: "+p));
        for (String code : def.skills()) {
            if(code==null||code.isBlank()){issue(errors,"skills","技能标识不能为空");continue;}
            try {var entity=resources.require(code);if(!entity.kind.equals("SKILL"))throw new IllegalArgumentException("资源不是技能");
                Integer pin=def.skillVersions().get(code);if(pin==null&&skills.get(code).isEmpty())issue(errors,"skillVersions."+code,"必须显式选择已发布技能版本");
                if(pin!=null&&pin<1)issue(errors,"skillVersions."+code,"技能版本必须为正整数");
            }catch(RuntimeException e){issue(errors,"skills",e.getMessage());}
        }
        if (def.tools().stream().anyMatch(Objects::isNull)) issue(errors,"tools","工具标识不能为空");
        boolean supervisor = "SUPERVISOR".equals(def.type());
        for (String code : def.tools()) {
            if(code==null)continue;
            try {var entity=resources.require(code);if(!entity.kind.equals("TOOL"))throw new IllegalArgumentException("不是工具资源");
            if (resources.parse(entity.draftJson).path("config").path("sideEffect").asText().equals("WRITE") && (supervisor || !def.policies().writeToolsAllowed())) issue(errors,"tools","未授权写工具: "+code);
            }catch(RuntimeException e){issue(errors,"tools",e.getMessage());}
        }
        if(def.policies().maxToolRounds()<0||def.policies().maxToolRounds()>20)issue(errors,"policies.maxToolRounds","范围0–20");
        if(def.policies().toolTimeoutSeconds()<1||def.policies().toolTimeoutSeconds()>300)issue(errors,"policies.toolTimeoutSeconds","范围1–300秒");
        if(def.policies().taskTimeoutSeconds()<1||def.policies().taskTimeoutSeconds()>600)issue(errors,"policies.taskTimeoutSeconds","范围1–600秒");
        if(def.memory().windowSize()<0||def.memory().windowSize()>50)issue(errors,"memory.windowSize","范围0–50轮");
        if(!def.modelConfig().inheritDefaults())issue(errors,"modelConfig.inheritDefaults","当前版本须继承模型资源参数");
        if(activating&&errors.isEmpty())try{resourceResolver.resolve(def);}catch(RuntimeException e){issue(errors,"resources",e.getMessage());}
        if (supervisor && def.policies().writeToolsAllowed()) issue(errors,"policies.allowWriteTools","主Agent禁止写工具");
        if (!supervisor && !def.children().isEmpty()) issue(errors,"children","仅主Agent可关联子Agent");
        if (supervisor) {
            if (activating && def.children().stream().noneMatch(c -> c != null && c.childEnabled())) issue(errors,"children","启用主Agent至少需要一个已启用子Agent");
            Set<String> seen = new HashSet<>();
            for (var child : def.children()) {
                if (child==null || child.agentCode()==null || child.agentCode().isBlank()) { issue(errors,"children","子Agent标识必填"); continue; }
                String code = child.agentCode();
                if (!seen.add(code)) issue(errors,"children","重复关联: "+code);
                if (code.equals(def.code())) issue(errors,"children","不能关联自身");
                var entity = agents.findByCode(code);
                if (entity.isEmpty()) issue(errors,"children","子Agent不存在: "+code);
                else if (!"WORKER".equals(entity.get().getType())) issue(errors,"children","仅可关联WORKER: "+code);
                else if (activating && child.childEnabled() && (!entity.get().isEnabled() || entity.get().getPublishedVersion()==null)) issue(errors,"children","子Agent尚未启用: "+code);
            }
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }
    private void issue(List<ValidationIssue> errors, String path, String message) { errors.add(new ValidationIssue(path,message)); }
    public record ValidationResult(boolean ok, List<ValidationIssue> errors, List<ValidationIssue> warnings) {}
}
