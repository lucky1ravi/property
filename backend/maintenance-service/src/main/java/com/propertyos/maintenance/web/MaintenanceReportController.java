package com.propertyos.maintenance.web;

import com.propertyos.common.rls.Db;
import com.propertyos.common.rls.ReportRows;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repair reports: what repairs cost and how long they take, grouped by property, category or vendor, the jobs behind each group,
 * and a scorecard for every vendor. Each answers {@code {summary, rows, total, page, size}} with the usual paging, sorting, search
 * and {@code f.<column>} filters. Owners see their own properties, by row-level security.
 */
@RestController
@RequestMapping("/api/maintenance/reports")
public class MaintenanceReportController {
    private final Db db;

    public MaintenanceReportController(Db db) {
        this.db = db;
    }

    private static LocalDate day(String s, LocalDate fallback) {
        return s == null || s.isBlank() ? fallback : Req.date(Map.of("d", s), "d");
    }

    /** Jobs, cost and speed grouped by {@code by}: property (default), category, vendor or priority. Period: {@code from} and {@code to} (default the last 12 months). */
    @GetMapping("/costs") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> costs(@RequestParam(defaultValue = "property") String by, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = day(to, LocalDate.now()), start = day(from, end.minusMonths(12));
        if (start.isAfter(end)) throw ApiException.badRequest("The start date must be before the end date.");
        String[] g = switch (by) {
            case "property" -> new String[]{"w.property_id", "w.property_name"};
            case "category" -> new String[]{"w.category", "w.category"};
            case "vendor" -> new String[]{"w.vendor_id", "coalesce(w.vendor_name, 'Not assigned')"};
            case "priority" -> new String[]{"w.priority", "w.priority"};
            default -> throw ApiException.badRequest("Group by property, category, vendor or priority.");
        };
        List<Object> a = new ArrayList<>(List.of(start.toString(), end.toString()));
        String base = " from maintenance.work_order w where w.created_at::date between ?::date and ?::date";
        return ReportRows.of(db, """
                select count(*) as jobs, count(*) filter (where w.status in ('COMPLETED','CLOSED')) as completed, coalesce(sum(w.final_cost), 0) as total_cost,
                       coalesce(round(avg(w.final_cost) filter (where w.final_cost is not null), 2), 0) as avg_cost, count(*) filter (where w.sla_breached) as sla_breaches,
                       count(*) filter (where w.priority = 'EMERGENCY') as emergencies
                """ + base, a,
                "select " + g[0] + "::text as group_id, " + g[1] + " as name, count(*) as jobs, count(*) filter (where w.status in ('COMPLETED','CLOSED')) as completed, "
                        + "coalesce(sum(w.final_cost), 0) as total_cost, coalesce(round(avg(w.final_cost) filter (where w.final_cost is not null), 2), 0) as avg_cost, "
                        + "coalesce(round((avg(extract(epoch from (w.completed_at - w.created_at)) / 86400) filter (where w.completed_at is not null))::numeric, 1), 0) as avg_days_to_finish, "
                        + "count(*) filter (where w.sla_breached) as sla_breaches, count(*) filter (where w.priority = 'EMERGENCY') as emergencies"
                        + base + " group by " + g[0] + ", " + g[1] + " order by total_cost desc, jobs desc", a);
    }

    /**
     * The jobs behind a figure. Filter with {@code propertyId}, {@code vendorId}, {@code category} and {@code priority}; newest first. Period: {@code from}, {@code to}.
     */
    @GetMapping("/jobs") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> jobs(@RequestParam(required = false) UUID propertyId, @RequestParam(required = false) UUID vendorId, @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String priority, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = day(to, LocalDate.now()), start = day(from, end.minusMonths(12));
        List<Object> a = new ArrayList<>(List.of(start.toString(), end.toString()));
        StringBuilder where = new StringBuilder(" where w.created_at::date between ?::date and ?::date");
        if (propertyId != null) { where.append(" and w.property_id = ?"); a.add(propertyId); }
        if (vendorId != null) { where.append(" and w.vendor_id = ?"); a.add(vendorId); }
        if (category != null && !category.isBlank()) { where.append(" and w.category = ?"); a.add(category.toUpperCase()); }
        if (priority != null && !priority.isBlank()) { where.append(" and w.priority = ?"); a.add(priority.toUpperCase()); }
        String base = " from maintenance.work_order w" + where;
        return ReportRows.of(db, "select count(*) as jobs, coalesce(sum(w.final_cost), 0) as total_cost, coalesce(sum(w.estimate), 0) as estimated" + base, a,
                "select w.id, w.property_id, w.property_name, w.unit_label, w.title, w.category, w.priority, w.status, w.vendor_id, w.vendor_name, w.estimate, w.final_cost, "
                        + "w.sla_breached, w.created_at, w.completed_at, w.rating" + base + " order by w.created_at desc", a);
    }

    /** A scorecard per vendor for the period: jobs, cost against estimate, speed, on-time share, and what people rate them. */
    @GetMapping("/vendors") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> vendors(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = day(to, LocalDate.now()), start = day(from, end.minusMonths(12));
        if (start.isAfter(end)) throw ApiException.badRequest("The start date must be before the end date.");
        List<Object> a = new ArrayList<>(List.of(start.toString(), end.toString()));
        String rows = """
                select v.id as vendor_id, v.name, v.active,
                       count(w.id) as jobs, count(w.id) filter (where w.status in ('COMPLETED','CLOSED')) as completed,
                       coalesce(sum(w.final_cost), 0) as total_cost, coalesce(round(avg(w.final_cost) filter (where w.final_cost is not null), 2), 0) as avg_cost,
                       coalesce(round((avg(w.final_cost / nullif(w.estimate, 0)) filter (where w.final_cost is not null and w.estimate > 0))::numeric, 2), 0) as cost_vs_estimate,
                       coalesce(round(100.0 * count(w.id) filter (where not w.sla_breached) / nullif(count(w.id), 0), 1), 0) as on_time_pct,
                       coalesce((select round(avg(r.stars), 2) from maintenance.vendor_review r where r.vendor_id = v.id), v.rating) as rating,
                       (select count(*) from maintenance.vendor_review r where r.vendor_id = v.id) as ratings,
                       v.insurance_expires
                from maintenance.vendor v left join maintenance.work_order w on w.vendor_id = v.id and w.created_at::date between ?::date and ?::date
                group by v.id order by completed desc, v.name""";
        return ReportRows.of(db, "select count(*) as vendors, count(*) filter (where active) as active from maintenance.vendor", new ArrayList<>(), rows, a);
    }
}
