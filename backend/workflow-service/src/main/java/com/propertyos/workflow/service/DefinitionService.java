package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.workflow.DefaultWorkflows;
import com.propertyos.common.workflow.WorkflowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Per-organisation workflow definitions. Built-ins are the default; edits are validated, versioned and reversible. */
@Service
public class DefinitionService {
    private final Db db;
    private final DefaultWorkflows defaults;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public DefinitionService(Db db, DefaultWorkflows defaults, ObjectMapper mapper, AuditService audit) {
        this.db = db;
        this.defaults = defaults;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkflowDefinition d : defaults.all()) {
            Optional<Map<String, Object>> o = db.find("select version, updated_by, updated_at from workflow.workflow_def where key = ?", d.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key());
            m.put("name", d.name());
            m.put("entity", d.entity());
            m.put("states", d.states().size());
            m.put("transitions", d.transitions().size());
            m.put("customised", o.isPresent());
            m.put("version", o.map(x -> x.get("version")).orElse(1));
            m.put("updatedBy", o.map(x -> x.get("updated_by")).orElse(null));
            m.put("updatedAt", o.map(x -> x.get("updated_at")).orElse(null));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("key")));
        return out;
    }

    /** Effective definition for the current organisation. */
    @Transactional(readOnly = true)
    public JsonNode effective(String key) {
        Optional<Map<String, Object>> o = db.find("select definition, version from workflow.workflow_def where key = ?", key);
        if (o.isPresent()) {
            com.fasterxml.jackson.databind.node.ObjectNode n = ((JsonNode) o.get().get("definition")).deepCopy();
            n.put("version", ((Number) o.get().get("version")).intValue());
            return n;
        }
        WorkflowDefinition d = defaults.get(key);
        if (d == null) throw ApiException.notFound("Workflow");
        return mapper.valueToTree(d);
    }

    @Transactional(readOnly = true)
    public JsonNode defaultOf(String key) {
        WorkflowDefinition d = defaults.get(key);
        if (d == null) throw ApiException.notFound("Workflow");
        return mapper.valueToTree(d);
    }

    @Transactional
    public JsonNode save(String key, JsonNode definition) {
        if (defaults.get(key) == null) throw ApiException.notFound("Workflow");
        List<String> problems = validate(key, definition);
        if (!problems.isEmpty()) throw ApiException.badRequest(String.join(" ", problems));
        int next = db.find("select version from workflow.workflow_def where key = ?", key).map(m -> ((Number) m.get("version")).intValue() + 1).orElse(2);
        String who = UserContextHolder.require().name();
        db.update("""
                insert into workflow.workflow_def (id, org_id, key, version, definition, updated_by) values (?, ?, ?, ?, ?::jsonb, ?)
                on conflict (org_id, key) do update set version = excluded.version, definition = excluded.definition, updated_by = excluded.updated_by, updated_at = now()""",
                UUID.randomUUID(), UserContextHolder.orgId(), key, next, db.json(definition), who);
        db.update("insert into workflow.workflow_def_history (id, org_id, key, version, definition, changed_by) values (?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), UserContextHolder.orgId(), key, next, db.json(definition), who);
        audit.record("Workflow", UUID.nameUUIDFromBytes(key.getBytes()), "WORKFLOW_UPDATED", null, null, Map.of("key", key, "version", next));
        return effective(key);
    }

    @Transactional
    public void reset(String key) {
        db.update("delete from workflow.workflow_def where key = ?", key);
        audit.record("Workflow", UUID.nameUUIDFromBytes(key.getBytes()), "WORKFLOW_RESET", null, null, Map.of("key", key));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String key) {
        return db.list("select version, changed_by, changed_at from workflow.workflow_def_history where key = ? order by version desc", key);
    }

    /** Safety rules so a customised workflow can never dead-end or break the platform's built-in steps. */
    List<String> validate(String key, JsonNode def) {
        List<String> problems = new ArrayList<>();
        WorkflowDefinition base = defaults.get(key);
        Set<String> states = new HashSet<>();
        for (JsonNode s : def.path("states")) states.add(s.path("key").asText());
        if (states.isEmpty()) problems.add("Add at least one state.");
        if (!states.contains(def.path("initialState").asText())) problems.add("The initial state must be one of the states.");
        Set<String> actions = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (JsonNode t : def.path("transitions")) {
            String a = t.path("action").asText();
            if (a.isBlank()) problems.add("Every step needs an action name.");
            if (!states.contains(t.path("to").asText())) problems.add("Step '" + a + "' leads to an unknown state.");
            for (JsonNode f : t.path("from")) if (!states.contains(f.asText())) problems.add("Step '" + a + "' starts from an unknown state '" + f.asText() + "'.");
            actions.add(a);
            targets.add(t.path("to").asText());
        }
        for (WorkflowDefinition.Transition t : base.transitions())
            if (t.system() && !actions.contains(t.action())) problems.add("The automatic step '" + t.label() + "' is required by the platform and can't be removed.");
        for (WorkflowDefinition.State s : base.states())
            if (!states.contains(s.key()) && base.transitions().stream().anyMatch(t -> t.to().equals(s.key()) || t.from().contains(s.key())))
                problems.add("State '" + s.label() + "' is used by the platform and can't be removed (rename its label instead).");
        for (String st : states) {
            boolean terminal = def.path("states").findValues("key").isEmpty();
            boolean leaves = false;
            for (JsonNode t : def.path("transitions")) for (JsonNode f : t.path("from")) if (f.asText().equals(st)) leaves = true;
            boolean category = false;
            for (JsonNode s : def.path("states")) if (s.path("key").asText().equals(st) && List.of("DONE", "CANCELLED").contains(s.path("category").asText())) category = true;
            if (!leaves && !category) problems.add("State '" + st + "' has no way out. Add a step from it or mark it as a final state.");
        }
        return problems;
    }
}
