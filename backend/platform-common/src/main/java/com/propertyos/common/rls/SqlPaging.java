package com.propertyos.common.rls;

import com.propertyos.common.web.PageParams;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Wraps a list query so the database does the paging, sorting, searching and column filtering the request asked for
 * (see {@link PageParams}). Shared by {@link Db} and by services that use a plain {@link JdbcTemplate}.
 */
public final class SqlPaging {
    private SqlPaging() {
    }

    /**
     * Runs the query and returns the requested page, or every row when the request did not ask for a page.
     * When it answers a page it records the total so the response is wrapped as {@code {rows, total, page, size}}.
     *
     * @param sql  the SQL with {@code ?} placeholders, without a trailing semicolon; it may keep its own {@code order by}
     * @param args values for the placeholders
     */
    public static List<Map<String, Object>> page(JdbcTemplate jdbc, String sql, Object... args) {
        Optional<PageParams.Query> asked = PageParams.current();
        if (asked.isEmpty()) return jdbc.queryForList(sql, args);
        PageParams.Query q = asked.get();
        String from = " from (" + sql + ") t";
        Set<String> columns = new HashSet<>();
        if (q.sort() != null || !q.text().isEmpty() || !q.from().isEmpty() || !q.to().isEmpty() || !q.numbers().isEmpty()) {
            // Only columns the query really returns can be sorted or filtered on; anything else is ignored rather than trusted.
            columns = jdbc.query("select *" + from + " limit 0", rs -> {
                Set<String> names = new HashSet<>();
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) names.add(md.getColumnLabel(i).toLowerCase());
                return names;
            }, args);
        }
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>(Arrays.asList(args));
        if (q.q() != null) { where.add("t::text ilike ?"); params.add(like(q.q())); }
        for (var e : q.text().entrySet()) if (columns.contains(e.getKey())) { where.add("t.\"" + e.getKey() + "\"::text ilike ?"); params.add(like(e.getValue())); }
        for (var e : q.from().entrySet()) if (columns.contains(e.getKey()) && isoDate(e.getValue())) { where.add("t.\"" + e.getKey() + "\"::date >= ?::date"); params.add(e.getValue().substring(0, 10)); }
        for (var e : q.to().entrySet()) if (columns.contains(e.getKey()) && isoDate(e.getValue())) { where.add("t.\"" + e.getKey() + "\"::date <= ?::date"); params.add(e.getValue().substring(0, 10)); }
        for (var e : q.numbers().entrySet()) {
            if (!columns.contains(e.getKey())) continue;
            String[] p = e.getValue().split(":");
            String op = switch (p[0]) { case "eq" -> "="; case "ne" -> "<>"; case "gt" -> ">"; case "ge" -> ">="; case "lt" -> "<"; case "le" -> "<="; default -> p[0].equals("between") ? "between" : null; };
            if (op == null || p.length < 2 || !isNumber(p[1])) continue;
            String col = "t.\"" + e.getKey() + "\"::numeric";
            if (op.equals("between")) {
                if (p.length >= 3 && isNumber(p[2])) { where.add(col + " between least(?::numeric, ?::numeric) and greatest(?::numeric, ?::numeric)"); params.addAll(List.of(p[1], p[2], p[1], p[2])); }
                else { where.add(col + " >= ?::numeric"); params.add(p[1]); }
            } else { where.add(col + " " + op + " ?::numeric"); params.add(p[1]); }
        }
        String filtered = from + (where.isEmpty() ? "" : " where " + String.join(" and ", where));
        Long total = jdbc.queryForObject("select count(*)" + filtered, Long.class, params.toArray());
        String order = "";
        if (q.sort() != null && columns.contains(q.sort())) {
            order = " order by t.\"" + q.sort() + "\" " + (q.desc() ? "desc" : "asc") + " nulls last" + (columns.contains("id") && !q.sort().equals("id") ? ", t.id" : "");
        }
        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(q.size());
        dataParams.add(q.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("select t.*" + filtered + order + " limit ? offset ?", dataParams.toArray());
        PageParams.answer(q, total == null ? 0 : total);
        return rows;
    }

    private static String like(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean isoDate(String s) {
        return s != null && s.length() >= 10 && s.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static boolean isNumber(String s) {
        return s != null && s.matches("-?\\d+(\\.\\d+)?");
    }

}
