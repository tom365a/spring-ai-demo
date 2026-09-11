package com.demo.cs;

import com.demo.cs.infrastructure.vector.LexicalFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 ONNX 中文嵌入（bge-small-zh-v1.5）的真实语义检索。
 * 关键断言是「换一种说法也能命中」——这正是关键词匹配做不到的事。
 */
class LocalEmbeddingRetrievalTest {

    static final String REFUND = "国内订单签收后 7 天内可以无理由退货，家电类目延长到 15 天。";
    static final String ACCOUNT = "忘记登录口令时，可以通过绑定的手机号码重新设置。";
    static final String SHIPPING = "顺丰一般 2 到 3 天送达，偏远地区约 5 天。";

    static EmbeddingModel model;

    @BeforeAll
    static void loadModel() throws Exception {
        TransformersEmbeddingModel m = new TransformersEmbeddingModel();
        m.setTokenizerResource(new ClassPathResource("models/bge-small-zh/tokenizer.json"));
        m.setModelResource(new ClassPathResource("models/bge-small-zh/model.onnx"));
        m.setResourceCacheDirectory(System.getProperty("java.io.tmpdir") + "/spring-ai-onnx-test-cache");
        m.afterPropertiesSet();
        model = m;
    }

    @Test
    void producesRealDenseVectorsLocally() {
        float[] v = model.embed("退货政策");
        assertThat(v).hasSize(512);
        // 不是全零、也不是常量向量
        var values = java.util.stream.IntStream.range(0, v.length).mapToObj(i -> v[i]).toList();
        assertThat(values).anyMatch(x -> x != 0f);
        assertThat(values.stream().distinct().count()).isGreaterThan(50L);

        // 同义句距离应明显小于无关句
        double near = cosine(model.embed("怎么退货"), model.embed("如何办理退款退回商品"));
        double far = cosine(model.embed("怎么退货"), model.embed("顺丰快递几天能到"));
        assertThat(near).isGreaterThan(far);
    }

    @Test
    void paraphraseFindsTheRightDocumentWhereKeywordsWouldNot(@TempDir Path dir) {
        SimpleVectorStore store = SimpleVectorStore.builder(model).build();
        store.add(List.of(new Document(REFUND), new Document(ACCOUNT), new Document(SHIPPING)));

        // 这句话与退货那条几乎没有共同词：没有「退货」「订单」「签收」「无理由」
        String paraphrase = "买回来觉得不合适，还能不能把东西送回去";
        List<Document> hits = store.similaritySearch(SearchRequest.builder().query(paraphrase).topK(1).build());
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().getText()).isEqualTo(REFUND);

        // 另一个方向同样成立
        List<Document> pwd = store.similaritySearch(
                SearchRequest.builder().query("进不去账号了，密码想不起来").topK(1).build());
        assertThat(pwd.getFirst().getText()).isEqualTo(ACCOUNT);
    }

    @Test
    void keywordStoreMissesTheSameParaphrase(@TempDir Path dir) {
        // 对照组：同样三条文档、同样的问法，关键词检索给不出退货那条
        LexicalFileStore lexical = new LexicalFileStore(new ObjectMapper(),
                dir.resolve("lexical.json"), dir.resolve("vector.json"));
        lexical.add(List.of(new Document(REFUND), new Document(ACCOUNT), new Document(SHIPPING)));

        List<Document> hits = lexical.similaritySearch(
                SearchRequest.builder().query("买回来觉得不合适，还能不能把东西送回去").topK(1).build());
        boolean keywordFound = !hits.isEmpty() && REFUND.equals(hits.getFirst().getText());
        assertThat(keywordFound)
                .as("若关键词检索也能命中，这个对照就失去意义，需要换一个更强的改写例子")
                .isFalse();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}
