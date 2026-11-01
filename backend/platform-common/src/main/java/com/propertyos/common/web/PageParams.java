package com.propertyos.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The paging, sorting, search and column-filter parameters of the current request.
 *
 * <p>Any list endpoint can be paged without changing its signature: when the request carries {@code page}, the list queries
 * that use {@code Db.page} return only that page and {@link PagedResponseAdvice} wraps it as
 * {@code {rows, total, page, size}}. A request without {@code page} gets the plain list exactly as before, so dropdowns and
 * other callers are unaffected.
 *
 * <p>Parameters: {@code page} (from 1), {@code size} (default 20, at most 100), {@code sort}, {@code dir} (asc or desc),
 * {@code q} (search every column), {@code f.<column>} (contains), {@code from.<column>} and {@code to.<column>} (dates),
 * {@code num.<column>} ({@code op:a} or {@code between:a:b}). {@code export=1} raises the size limit to {@value #EXPORT_MAX}.
 */
public final class PageParams {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int EXPORT_MAX = 5000;
    static final String TOTAL = "pos.page.total";
    static final String SIZE = "pos.page.size";
    static final String PAGE = "pos.page.page";
    static final String USED = "pos.page.used";
    private static final Pattern COLUMN = Pattern.compile("[a-z_][a-z0-9_]*");

    private PageParams() {
    }

    /**
     * What the caller asked for.
     *
     * @param page    page number from 1
     * @param size    rows per page
     * @param sort    column to sort by, or null for the query's own order
     * @param desc    true for descending
     * @param q       text to find in any column, or null
     * @param text    column to "contains" text
     * @param from    column to earliest date (inclusive)
     * @param to      column to latest date (inclusive)
     * @param numbers column to {@code op:a[:b]}
     */
    public record Query(int page, int size, String sort, boolean desc, String q, Map<String, String> text, Map<String, String> from,
                        Map<String, String> to, Map<String, String> numbers) {
        /** Rows to skip. */
        public long offset() {
            return (long) (page - 1) * size;
        }
    }

    /**
     * Reads the paging parameters of the request being handled.
     *
     * @return the query, or empty when there is no request or it did not ask for a page
     */
    public static Optional<Query> current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return Optional.empty();
        HttpServletRequest r = sra.getRequest();
        if (r.getParameter("page") == null) return Optional.empty();
        // A page request is answered once; a second list query in the same request is returned in full.
        if (r.getAttribute(USED) != null) return Optional.empty();
        boolean export = "1".equals(r.getParameter("export"));
        int size = clamp(intOf(r.getParameter("size"), DEFAULT_SIZE), 1, export ? EXPORT_MAX : MAX_SIZE);
        int page = Math.max(1, intOf(r.getParameter("page"), 1));
        String sort = r.getParameter("sort");
        if (sort != null && !COLUMN.matcher(sort).matches()) sort = null;
        Map<String, String> text = new LinkedHashMap<>(), from = new LinkedHashMap<>(), to = new LinkedHashMap<>(), numbers = new LinkedHashMap<>();
        r.getParameterMap().forEach((k, v) -> {
            if (v.length == 0 || v[0] == null || v[0].isBlank()) return;
            int dot = k.indexOf('.');
            if (dot < 0 || !COLUMN.matcher(k.substring(dot + 1)).matches()) return;
            String col = k.substring(dot + 1), val = v[0].trim();
            switch (k.substring(0, dot)) {
                case "f" -> text.put(col, val);
                case "from" -> from.put(col, val);
                case "to" -> to.put(col, val);
                case "num" -> numbers.put(col, val);
                default -> { }
            }
        });
        String q = r.getParameter("q");
        return Optional.of(new Query(page, size, sort, "desc".equalsIgnoreCase(r.getParameter("dir")),
                q == null || q.isBlank() ? null : q.trim(), text, from, to, numbers));
    }

    /** Records the answer so {@link PagedResponseAdvice} can wrap the list. */
    static void publish(Query query, long total) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return;
        HttpServletRequest r = sra.getRequest();
        r.setAttribute(USED, Boolean.TRUE);
        r.setAttribute(TOTAL, total);
        r.setAttribute(PAGE, query.page());
        r.setAttribute(SIZE, query.size());
    }

    /**
     * For endpoints that answer with a map holding the rows (rather than a bare list): adds {@code total}, {@code page} and
     * {@code size} when this request was answered one page at a time.
     */
    public static void addTo(Map<String, Object> out) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return;
        HttpServletRequest r = sra.getRequest();
        if (r.getAttribute(TOTAL) == null) return;
        out.put("total", r.getAttribute(TOTAL));
        out.put("page", r.getAttribute(PAGE));
        out.put("size", r.getAttribute(SIZE));
    }

    /** Public entry for code outside this package that pages a list itself. */
    public static void answer(Query query, long total) {
        publish(query, total);
    }

    private static int intOf(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
