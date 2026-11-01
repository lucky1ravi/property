package com.propertyos.common.rls;

import com.propertyos.common.web.PageParams;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape every report answers in: a few headline figures ({@code summary}) and the rows behind them, paged, sorted, searched and
 * filtered by column like any other list, so the screen can drill from a figure to the records and export them.
 */
public final class ReportRows {
    private ReportRows() {
    }

    /**
     * @param db         the database access of the calling service
     * @param summarySql one row of headline figures, or null for none
     * @param summaryArgs values for the summary query
     * @param rowsSql    the rows, with any joins; may keep its own {@code order by}
     * @param rowsArgs   values for the rows query
     */
    public static Map<String, Object> of(Db db, String summarySql, List<Object> summaryArgs, String rowsSql, List<Object> rowsArgs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summarySql == null ? Map.of() : db.list(summarySql, summaryArgs.toArray()).get(0));
        out.put("rows", db.page(rowsSql, rowsArgs.toArray()));
        PageParams.addTo(out);
        return out;
    }
}
