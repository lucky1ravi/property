package com.propertyos.common.rls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.web.ApiException;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * Returns the underlying template for the rare case that raw access is needed.
     *
     * @return the shared {@link JdbcTemplate}
     */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Fails closed when a query runs outside a transaction. Row-level security settings are written when a transaction begins,
     * so a query without one would run as the connection's own (privileged) user and could read other companies' rows.
     */
    private static void requireTransaction() {
        if (RlsTransactionManager.roleSwitchEnabled() && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Database access outside a transaction would bypass row-level security. Annotate the service method with @Transactional.");
        }
    }

    /**
     * Runs a query and returns every row as a map of column to value.
     *
     * Runs inside the caller's transaction so row-level security applies.
     *
     * @param sql  the SQL with {@code ?} placeholders
     * @param args values for the placeholders
     * @return the rows, possibly empty
     * @throws IllegalStateException if row-level security is active and no transaction is open
     */
    public List<Map<String, Object>> list(String sql, Object... args) {
        requireTransaction();
        return jdbc.queryForList(sql, args).stream().map(this::clean).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * Runs a query and returns the first row, if any.
     *
     * @param sql  the SQL with {@code ?} placeholders
     * @param args values for the placeholders
     * @return the first row, or empty when nothing matched
     */
    public Optional<Map<String, Object>> find(String sql, Object... args) {
        return list(sql, args).stream().findFirst();
    }

    /**
     * Runs a query and returns the first row, or fails with "not found".
     *
     * @param what a person-readable name of the thing sought, used in the error message
     * @param sql  the SQL with {@code ?} placeholders
     * @param args values for the placeholders
     * @return the first row
     * @throws ApiException (404) when no row matched
     */
    public Map<String, Object> one(String what, String sql, Object... args) {
        return find(sql, args).orElseThrow(() -> ApiException.notFound(what));
    }

    /**
     * Runs an insert, update or delete.
     *
     * @param sql  the SQL with {@code ?} placeholders
     * @param args values for the placeholders
     * @return the number of rows changed
     * @throws IllegalStateException if row-level security is active and no transaction is open
     */
    public int update(String sql, Object... args) {
        requireTransaction();
        return jdbc.update(sql, args);
    }

    /**
     * Runs a query that returns a single value.
     *
     * @param type the expected Java type of the value
     * @param sql  the SQL with {@code ?} placeholders
     * @param args values for the placeholders
     * @return the value
     */
    public <T> T value(Class<T> type, String sql, Object... args) {
        requireTransaction();
        return jdbc.queryForObject(sql, type, args);
    }

    /**
     * Serialises a value to JSON text for storing in a {@code jsonb} column.
     *
     * @param value any serialisable object, or null
     * @return the JSON text, or null when {@code value} is null
     */
    public String json(Object value) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Converts a value (JSON text, a node or a plain object) to a JSON tree.
     *
     * @param value the value to convert, or null
     * @return the tree; a null node when {@code value} is null or cannot be read
     */
    public JsonNode tree(Object value) {
        if (value == null) return mapper.nullNode();
        if (value instanceof JsonNode n) return n;
        try {
            return mapper.readTree(value.toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
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
