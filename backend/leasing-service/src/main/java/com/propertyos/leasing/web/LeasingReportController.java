package com.propertyos.leasing.web;

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
 * Reports about the portfolio: who lives where and what it brings in, how full the buildings are, which leases end soon, how
 * enquiries turn into leases, and what residents think. Every report answers {@code {summary, rows, total, page, size}} and
 * accepts the usual paging, sorting, search and {@code f.<column>} filters, so any figure can be followed down to its records.
 * What each person gets follows row-level security: an owner sees their own properties, property staff only theirs.
 */
@RestController
@RequestMapping("/api/leasing/reports")
public class LeasingReportController {
    private static final List<String> LIVE = List.of("ACTIVE", "RENEWAL_OFFERED", "NOTICE_GIVEN");
    private final Db db;

    public LeasingReportController(Db db) {
        this.db = db;
    }

    private static List<Object> none() {
        return new ArrayList<>();
    }

    /** One row per unit: who is in it, the lease dates, the rent against what the unit could earn, and the gap. Filter with {@code propertyId}. */
    @GetMapping("/rent-roll") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> rentRoll(@RequestParam(required = false) UUID propertyId) {
        String where = propertyId == null ? "" : " and p.id = ?";
        List<Object> args = new ArrayList<>();
        if (propertyId != null) args.add(propertyId);
        String base = """
                from leasing.unit u join leasing.property p on p.id = u.property_id
                left join leasing.lease l on l.unit_id = u.id and l.status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN')
                where u.archived_at is null and p.archived_at is null""" + where;
        return ReportRows.of(db, """
                select count(*) as units, count(*) filter (where l.id is not null) as occupied, coalesce(sum(l.rent), 0) as monthly_rent,
                       coalesce(sum(u.market_rent), 0) as market_potential,
                       coalesce(sum(case when l.id is not null then u.market_rent - l.rent else u.market_rent end), 0) as gap_to_market
                """ + base, args,
                """
                select u.id as unit_id, p.id as property_id, p.name as property, u.number as unit, u.beds, u.baths, u.sqft, u.status,
                       l.id as lease_id, l.tenant_id, l.tenant_name, l.start_date, l.end_date, l.rent, u.market_rent,
                       case when l.id is not null then u.market_rent - l.rent else u.market_rent end as gap_to_market
                """ + base + " order by p.name, u.number", args);
    }

    /** One row per property: how full it is, what the empty units cost each month, and how long they have stood empty. */
    @GetMapping("/occupancy") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> occupancy() {
        String base = """
                from leasing.property p left join leasing.unit u on u.property_id = p.id and u.archived_at is null
                where p.archived_at is null""";
        return ReportRows.of(db, """
                select count(u.id) as units, count(u.id) filter (where u.status = 'OCCUPIED') as occupied, count(u.id) filter (where u.status = 'VACANT') as vacant,
                       coalesce(round(100.0 * count(u.id) filter (where u.status = 'OCCUPIED') / nullif(count(u.id), 0), 1), 0) as occupancy_pct,
                       coalesce(sum(u.market_rent) filter (where u.status = 'VACANT'), 0) as vacancy_loss
                """ + base, none(),
                """
                select p.id as property_id, p.name as property, p.city, p.state, count(u.id) as units,
                       count(u.id) filter (where u.status = 'OCCUPIED') as occupied, count(u.id) filter (where u.status = 'VACANT') as vacant,
                       coalesce(round(100.0 * count(u.id) filter (where u.status = 'OCCUPIED') / nullif(count(u.id), 0), 1), 0) as occupancy_pct,
                       coalesce(sum(u.market_rent) filter (where u.status = 'VACANT'), 0) as vacancy_loss,
                       coalesce(round(avg(current_date - u.vacant_since) filter (where u.status = 'VACANT' and u.vacant_since is not null)), 0) as avg_days_vacant
                """ + base + " group by p.id order by p.name", none());
    }

    /** Units standing empty, longest first. Filter with {@code propertyId}. */
    @GetMapping("/vacancies") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> vacancies(@RequestParam(required = false) UUID propertyId) {
        List<Object> args = new ArrayList<>();
        if (propertyId != null) args.add(propertyId);
        String base = """
                from leasing.unit u join leasing.property p on p.id = u.property_id
                where u.status = 'VACANT' and u.archived_at is null and p.archived_at is null""" + (propertyId == null ? "" : " and p.id = ?");
        return ReportRows.of(db, "select count(*) as vacant, coalesce(sum(u.market_rent), 0) as lost_per_month, coalesce(round(avg(current_date - u.vacant_since)), 0) as avg_days_vacant " + base, args,
                """
                select u.id as unit_id, p.id as property_id, p.name as property, u.number as unit, u.beds, u.baths, u.sqft, u.market_rent, u.vacant_since,
                       coalesce(current_date - u.vacant_since, 0) as days_vacant,
                       (select l.status from leasing.listing l where l.unit_id = u.id and l.status <> 'LEASED' order by l.created_at desc limit 1) as listing_status
                """ + base + " order by u.vacant_since nulls last, p.name, u.number", args);
    }

    /** Leases ending in the next {@code days} days (default 120), soonest first, with whether a renewal has been offered. */
    @GetMapping("/expirations") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> expirations(@RequestParam(defaultValue = "120") int days) {
        if (days < 1 || days > 730) throw ApiException.badRequest("Choose between 1 and 730 days.");
        String base = " from leasing.lease l where l.status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') and l.end_date <= current_date + ?::int";
        List<Object> args = List.of(days);
        return ReportRows.of(db, """
                select count(*) as ending, count(*) filter (where l.end_date <= current_date + 30) as within_30,
                       count(*) filter (where l.status = 'RENEWAL_OFFERED') as renewal_offered, coalesce(sum(l.rent), 0) as monthly_rent_at_risk
                """ + base, args,
                """
                select l.id as lease_id, l.property_id, l.property_name as property, l.unit_label as unit, l.tenant_id, l.tenant_name, l.rent, l.start_date, l.end_date,
                       (l.end_date - current_date) as days_left, l.status,
                       case when l.end_date <= current_date + 30 then '0-30' when l.end_date <= current_date + 60 then '31-60' when l.end_date <= current_date + 90 then '61-90' else '90+' end as bucket
                """ + base + " order by l.end_date", args);
    }

    /** How enquiries turn into leases, by property, for a period (default the last 90 days): enquiries, tours, applications, approvals and signed leases. */
    @GetMapping("/funnel") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> funnel(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : Req.date(Map.of("d", to), "d");
        LocalDate start = from == null || from.isBlank() ? end.minusDays(90) : Req.date(Map.of("d", from), "d");
        if (start.isAfter(end)) throw ApiException.badRequest("The start date must be before the end date.");
        List<Object> a = List.of(start.toString(), end.toString());
        String per = """
                (select count(*) from leasing.lead x where x.property_id = p.id and x.created_at::date between ?::date and ?::date) as enquiries,
                (select count(*) from leasing.showing x where x.property_id = p.id and x.created_at::date between ?::date and ?::date) as tours,
                (select count(*) from leasing.showing x where x.property_id = p.id and x.status in ('ATTENDED','COMPLETED') and x.created_at::date between ?::date and ?::date) as tours_attended,
                (select count(*) from leasing.application x where x.property_id = p.id and x.submitted_at::date between ?::date and ?::date) as applications,
                (select count(*) from leasing.application x where x.property_id = p.id and x.status in ('APPROVED','LEASED') and x.submitted_at::date between ?::date and ?::date) as approved,
                (select count(*) from leasing.lease x where x.property_id = p.id and x.status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN','ENDED') and x.created_at::date between ?::date and ?::date) as leases""";
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 6; i++) args.addAll(a);
        String rows = "select p.id as property_id, p.name as property, " + per + " from leasing.property p where p.archived_at is null order by p.name";
        Map<String, Object> out = ReportRows.of(db, null, none(), rows, args);
        long enq = 0, apps = 0, leases = 0, tours = 0, approved = 0;
        for (Object o : (List<?>) out.get("rows")) {
            Map<?, ?> r = (Map<?, ?>) o;
            enq += ((Number) r.get("enquiries")).longValue(); tours += ((Number) r.get("tours")).longValue(); apps += ((Number) r.get("applications")).longValue();
            approved += ((Number) r.get("approved")).longValue(); leases += ((Number) r.get("leases")).longValue();
        }
        out.put("summary", Map.of("from", start.toString(), "to", end.toString(), "enquiries", enq, "tours", tours, "applications", apps, "approved", approved, "leases", leases,
                "enquiryToLeasePct", enq == 0 || leases > enq ? 0 : Math.round(1000.0 * leases / enq) / 10.0));
        return out;
    }

    /** What residents say, by property and unit: average, number of ratings and the latest comment. Filter with {@code f.subject_type=UNIT}. */
    @GetMapping("/ratings") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> ratings() {
        String base = """
                from leasing.review r left join leasing.property p on p.id = r.property_id left join leasing.unit u on u.id = r.unit_id group by r.subject_type, r.subject_id, p.id, p.name, u.number""";
        return ReportRows.of(db, "select count(*) as ratings, coalesce(round(avg(stars), 2), 0) as average, count(*) filter (where stars <= 2) as low from leasing.review", none(),
                """
                select r.subject_type, r.subject_id, p.id as property_id, p.name as property, u.number as unit, round(avg(r.stars), 2) as average, count(*) as ratings,
                       count(*) filter (where r.stars <= 2) as low_ratings, max(r.created_at) as latest
                """ + base + " order by average, ratings desc", none());
    }
}
