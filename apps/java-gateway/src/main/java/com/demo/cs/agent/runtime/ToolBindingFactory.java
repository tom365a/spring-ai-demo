package com.demo.cs.agent.runtime;

import com.demo.cs.infrastructure.catalog.LocalToolCatalog;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Component
public class ToolBindingFactory {

    private static final Logger log = LoggerFactory.getLogger(ToolBindingFactory.class);

    private final LocalToolCatalog catalog;
    private final OrderTicketTools orderTicketTools;

    public ToolBindingFactory(LocalToolCatalog catalog, OrderTicketTools orderTicketTools) {
        this.catalog = catalog;
        this.orderTicketTools = orderTicketTools;
    }

    public ToolCallback[] resolve(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return new ToolCallback[0];
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        for (String code : codes) {
            var entry = catalog.get(code);
            if (entry.isEmpty()) {
                log.warn("Skip unknown tool code: {}", code);
                continue;
            }
            try {
                callbacks.add(buildCallback(entry.get()));
            } catch (Exception e) {
                log.warn("Failed to bind tool {}: {}", code, e.getMessage());
            }
        }
        return callbacks.toArray(ToolCallback[]::new);
    }

    private ToolCallback buildCallback(LocalToolCatalog.ToolEntry entry) throws Exception {
        Method method = findMethod(entry.methodName());
        ToolDefinition fromMethod = ToolDefinitions.from(method);
        ToolDefinition definition = ToolDefinition.builder()
                .name(entry.code())
                .description(entry.description() != null ? entry.description() : fromMethod.description())
                .inputSchema(fromMethod.inputSchema())
                .build();
        return MethodToolCallback.builder()
                .toolDefinition(definition)
                .toolMethod(method)
                .toolObject(orderTicketTools)
                .build();
    }

    private Method findMethod(String methodName) throws NoSuchMethodException {
        for (Method m : OrderTicketTools.class.getMethods()) {
            if (m.getName().equals(methodName) && m.getDeclaringClass() == OrderTicketTools.class) {
                return m;
            }
        }
        throw new NoSuchMethodException(methodName);
    }
}
