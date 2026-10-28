package com.propertyos.common.rls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.web.ApiException;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Thin SQL helper returning API-ready rows: jsonb columns become JSON, dates ISO strings.
 * Must be used inside a transaction so row-level security applies.
 */
@Component
public class Db {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Db(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(this::clean).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public Optional<Map<String, Object>> find(String sql, Object... args) {
        return list(sql, args).stream().findFirst();
    }

    public Map<String, Object> one(String what, String sql, Object... args) {
        return find(sql, args).orElseThrow(() -> ApiException.notFound(what));
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public <T> T value(Class<T> type, String sql, Object... args) {
        return jdbc.queryForObject(sql, type, args);
    }

    public String json(Object value) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public JsonNode tree(Object value) {
        if (value == null) return mapper.nullNode();
        if (value instanceof JsonNode n) return n;
        try {
            return mapper.readTree(value.toString());
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }

    private Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            if (v instanceof PGobject pg && pg.getType() != null && pg.getType().startsWith("json")) {
                out.put(k, tree(pg.getValue()));
            } else if (v instanceof java.sql.Date d) {
                out.put(k, d.toLocalDate().toString());
            } else if (v instanceof java.sql.Timestamp t) {
                out.put(k, t.toInstant().toString());
            } else if (v instanceof java.sql.Array a) {
                try {
                    out.put(k, Arrays.asList((Object[]) a.getArray()));
                } catch (Exception e) {
                    out.put(k, List.of());
                }
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
