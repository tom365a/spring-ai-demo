package com.demo.cs;

import com.demo.cs.application.resources.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置工具元数据以代码目录为准。
 *
 * seed() 只在缺失时写入，而管理接口刻意禁止改内置工具的读写风险，
 * 所以目录改了之后老库会永远停在旧元数据上——启动对账是唯一合法的修正途径。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:builtin-reconcile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.agent-config.seed-on-startup=false", "app.upload-dir=./target/reconcile-test",
        "app.knowledge-sample-dir=./target/no-samples", "OPENAI_API_KEY=synthetic-self-test-only",
        "app.vector.backend=lexical", "spring.ai.model.embedding=none"})
@ActiveProfiles("local")
class BuiltinToolReconcileTest {

    @Autowired ResourceService resources;
    @Autowired ObjectMapper json;

    private String code() { return "b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private ObjectNode config(String sideEffect, boolean requireConfirm, int timeout) {
        ObjectNode c = json.createObjectNode().put("source", "BUILTIN").put("builtinCode", "probe")
                .put("sideEffect", sideEffect).put("requireConfirm", requireConfirm).put("timeoutSeconds", timeout);
        c.putObject("inputSchema").put("type", "object").putObject("properties");
        return c;
    }

    private JsonNode published(String code) {
        return resources.parse(json.valueToTree(resources.detail(code)).path("published").toString()).path("config");
    }

    @Test
    void riskDowngradeInCatalogReachesAnExistingRowAndClearsTheStaleConfirmation() {
        String code = code();
        resources.seed(code, "TOOL", "旧名字", "旧描述", config("WRITE", true, 30), null, true, false);
        assertThat(published(code).path("sideEffect").asText()).isEqualTo("WRITE");

        ObjectNode desired = config("READ", false, 30);
        desired.putObject("choice").put("prompt", "请确认").put("confirmLabel", "同意")
                .put("cancelLabel", "拒绝").put("rejectTool", "other_tool");
        resources.reconcileBuiltinTool(code, "新名字", "新描述", desired);

        JsonNode after = published(code);
        assertThat(after.path("sideEffect").asText()).isEqualTo("READ");
        // 已经不是写操作了，就不该再永远卡着一张确认卡片
        assertThat(after.path("requireConfirm").asBoolean()).isFalse();
        assertThat(after.path("choice").path("cancelLabel").asText()).isEqualTo("拒绝");
    }

    @Test
    void operatorTunedFieldsSurviveAndUnchangedCatalogPublishesNothing() {
        String code = code();
        resources.seed(code, "TOOL", "工具", "描述", config("WRITE", true, 30), null, true, false);
        String id = json.valueToTree(resources.detail(code)).path("id").asText();

        // 运维把超时调长，并保持 requireConfirm——这些是运维可调项，不该被对账冲掉
        ObjectNode body = (ObjectNode) json.valueToTree(resources.detail(code)).path("draft").deepCopy();
        ((ObjectNode) body.path("config")).put("timeoutSeconds", 55);
        body.put("draftRevision", json.valueToTree(resources.detail(code)).path("draftRevision").asInt());
        resources.save(id, body, true);
        resources.lifecycle(id, "publish", json.createObjectNode()
                .put("impactToken", String.valueOf(resources.impact(id, "publish").get("token"))));
        int versionBefore = json.valueToTree(resources.detail(code)).path("publishedVersion").asInt();

        // 目录没变：不应产生新版本
        resources.reconcileBuiltinTool(code, "工具", "描述", config("WRITE", true, 30));
        assertThat(json.valueToTree(resources.detail(code)).path("publishedVersion").asInt()).isEqualTo(versionBefore);
        assertThat(published(code).path("timeoutSeconds").asInt()).isEqualTo(55);
        assertThat(published(code).path("requireConfirm").asBoolean()).isTrue();
    }

    @Test
    void aDifferentBuiltinIdentityIsNeverOverwritten() {
        String code = code();
        resources.seed(code, "TOOL", "工具", "描述", config("WRITE", true, 30), null, true, false);
        ObjectNode desired = config("READ", false, 30);
        desired.put("builtinCode", "someone_else");   // 执行身份对不上，绝不能改人家的配置

        resources.reconcileBuiltinTool(code, "工具", "描述", desired);
        assertThat(published(code).path("sideEffect").asText()).isEqualTo("WRITE");
    }
}
