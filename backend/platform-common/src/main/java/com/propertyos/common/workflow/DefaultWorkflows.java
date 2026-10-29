package com.propertyos.common.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Built-in workflow templates; organisations copy and edit them in the workflow designer. */
@Component
public class DefaultWorkflows {
    private final Map<String, WorkflowDefinition> byKey = new LinkedHashMap<>();

    public DefaultWorkflows(ObjectMapper mapper) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:workflows/*.json");
        for (Resource r : resources) {
            WorkflowDefinition d = mapper.readValue(r.getInputStream(), WorkflowDefinition.class);
            byKey.put(d.key(), d);
        }
    }

    /**
     * Returns a built-in workflow by key.
     *
     * @param key the workflow key, for example {@code LEASE}
     * @return the definition, or null when unknown
     */
    public WorkflowDefinition get(String key) {
        return byKey.get(key);
    }

    /**
     * Returns every built-in workflow.
     */
    public Collection<WorkflowDefinition> all() {
        return byKey.values();
    }
}
