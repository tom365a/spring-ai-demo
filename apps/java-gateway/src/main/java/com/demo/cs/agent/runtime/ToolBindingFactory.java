package com.demo.cs.agent.runtime;

import com.demo.cs.infrastructure.catalog.LocalToolCatalog;
import com.demo.cs.infrastructure.tools.DefectCompensationTools;
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
    private final List<Object> toolBeans;

    public ToolBindingFactory(
            LocalToolCatalog catalog,
            OrderTicketTools orderTicketTools,
            DefectCompensationTools defectCompensationTools
    ) {
        this.catalog = catalog;
        this.toolBeans = List.of(orderTicketTools, defectCompensationTools);
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
        BoundMethod bound = findBoundMethod(entry.methodName());
        ToolDefinition fromMethod = ToolDefinitions.from(bound.method());
        ToolDefinition definition = ToolDefinition.builder()
                .name(entry.code())
                .description(entry.description() != null ? entry.description() : fromMethod.description())
                .inputSchema(fromMethod.inputSchema())
                .build();
        return MethodToolCallback.builder()
                .toolDefinition(definition)
                .toolMethod(bound.method())
                .toolObject(bound.bean())
                .build();
    }

    private BoundMethod findBoundMethod(String methodName) throws NoSuchMethodException {
        for (Object bean : toolBeans) {
            for (Method m : bean.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getDeclaringClass() == bean.getClass()) {
                    return new BoundMethod(bean, m);
                }
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private record BoundMethod(Object bean, Method method) {}
}
