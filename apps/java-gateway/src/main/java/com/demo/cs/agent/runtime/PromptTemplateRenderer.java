package com.demo.cs.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRenderer.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}");

    public static final Set<String> WHITELIST = Set.of(
            "userId", "summary", "recentMessages", "recentMessagesFormatted",
            "childrenCatalog", "agentDescription",
            "slots", "slotsJson", "locale", "visionSummary", "text",
            "retrievedBlocks", "mcpBlocks",
            "sessionStatus", "confirmationPayloadSummary",
            "hasAttachments", "attachmentCount", "transcript"
    );

    public String render(String template, Map<String, String> vars) {
        if (template == null || template.isBlank()) return "";
        Map<String, String> safe = vars != null ? vars : Map.of();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if (!WHITELIST.contains(key)) {
                log.warn("Unknown prompt placeholder left as-is: {}", m.group(0));
                replacement = m.group(0);
            } else {
                replacement = safe.getOrDefault(key, "");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Set<String> findUnknownPlaceholders(String template) {
        if (template == null || template.isBlank()) return Set.of();
        Matcher m = PLACEHOLDER.matcher(template);
        java.util.LinkedHashSet<String> unknown = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String key = m.group(1);
            if (!WHITELIST.contains(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }
}
