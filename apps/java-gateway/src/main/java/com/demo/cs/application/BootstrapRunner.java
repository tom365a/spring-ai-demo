package com.demo.cs.application;

import com.demo.cs.application.knowledge.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final KnowledgeService knowledgeService;

    public BootstrapRunner(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (knowledgeService.countDocs() == 0) {
                log.info("Knowledge base empty, bootstrapping sample documents...");
                var result = knowledgeService.bootstrapSamples();
                log.info("Bootstrapped {} knowledge documents", result.ingested());
            } else {
                log.info("Knowledge base has {} documents, skip bootstrap", knowledgeService.countDocs());
            }
        } catch (Exception e) {
            log.warn("Knowledge bootstrap skipped (check LLM/Embedding config): {}", e.getMessage());
        }
    }
}
