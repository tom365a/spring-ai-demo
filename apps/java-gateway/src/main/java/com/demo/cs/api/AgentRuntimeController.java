package com.demo.cs.api;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.config.AppProperties;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/agents/runtime")
public class AgentRuntimeController {
    private final DefinitionRegistry registry;
    private final AppProperties props;
    public AgentRuntimeController(DefinitionRegistry registry,AppProperties props) { this.registry=registry; this.props=props; }
    @GetMapping
    public ApiEnvelope<?> runtime() {
        var items=registry.allEnabled().values().stream().sorted(Comparator.comparing(a -> a.code())).map(a -> Map.of(
            "code",a.code(),"name",a.definition().name(),"type",a.definition().type(),"loadedVersion",a.version())).toList();
        return ApiEnvelope.ok(Map.of("configEnabled",props.agentConfig().enabled(),"items",props.agentConfig().enabled()?items:List.of()));
    }
}
