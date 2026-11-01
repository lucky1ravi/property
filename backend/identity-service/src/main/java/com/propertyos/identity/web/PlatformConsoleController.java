package com.propertyos.identity.web;

import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.demo.DemoData;
import com.propertyos.common.rls.SqlPaging;
import com.propertyos.common.rls.Tx;
import com.propertyos.common.security.RequiresPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What the platform operator needs to run the product: whether every service is up, and how much each company actually uses it.
 * Open to platform administrators only.
 */
@RestController
@RequestMapping("/api/identity/platform")
@RequiresPermission("PLATFORM:VIEW")
public class PlatformConsoleController {
    private final ServiceClient client;
    private final JdbcTemplate jdbc;
    private final Tx tx;

    public PlatformConsoleController(ServiceClient client, JdbcTemplate jdbc, Tx tx) {
        this.client = client;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Asks every service whether it is up and how quickly it answered. The services are asked at the same time, so a slow or
     * stopped one delays the page by its timeout at most, not by the sum of all of them.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        List<CompletableFuture<Map<String, Object>>> asked = new ArrayList<>();
        for (ServiceClient.Service s : ServiceClient.Service.values()) asked.add(CompletableFuture.supplyAsync(() -> client.probe(s)));
        List<Map<String, Object>> services = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> f : asked) services.add(f.join());
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("service", "DATABASE");
        long started = System.nanoTime();
        try {
            tx.read(() -> jdbc.queryForObject("select 1", Integer.class));
            db.put("status", "UP");
        } catch (RuntimeException e) {
            db.put("status", "DOWN");
            db.put("detail", e.getMessage());
        }
        db.put("millis", (System.nanoTime() - started) / 1_000_000);
        services.add(db);
        long down = services.stream().filter(m -> !"UP".equals(m.get("status"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", down == 0 ? "UP" : down == services.size() ? "DOWN" : "DEGRADED");
        out.put("up", services.size() - down);
        out.put("total", services.size());
        out.put("checkedAt", OffsetDateTime.now().toString());
        out.put("services", services);
        return out;
    }

    /**
     * One row per company: who is signed up, who really uses it, and whether it is going quiet. Sort, search and filter by any
     * column, for example {@code f.engagement=QUIET}.
     *
     * <p>Engagement: {@code NEVER} nobody has signed in yet, {@code ACTIVE} somebody signed in within 7 days, {@code SLOWING} within 30 days,
     * {@code QUIET} longer ago than that.</p>
     */
    @GetMapping("/usage")
    public List<Map<String, Object>> usage() {
        return tx.read(() -> SqlPaging.page(jdbc, """
                select o.id, o.name, o.plan, o.status, o.state, o.created_at,
                       count(u.id) as users,
                       count(u.id) filter (where u.last_login_at > now() - interval '7 days') as active_7d,
                       count(u.id) filter (where u.last_login_at > now() - interval '30 days') as active_30d,
                       max(u.last_login_at) as last_sign_in,
                       case when max(u.last_login_at) is null then 'NEVER'
                            when max(u.last_login_at) > now() - interval '7 days' then 'ACTIVE'
                            when max(u.last_login_at) > now() - interval '30 days' then 'SLOWING'
                            else 'QUIET' end as engagement,
                       (select count(*) from platform.audit_event a where a.org_id = o.id and a.created_at > now() - interval '30 days') as actions_30d
                from identity.organization o left join identity.app_user u on u.org_id = o.id
                where o.status <> 'DELETED' and o.id <> ?
                group by o.id order by max(u.last_login_at) desc nulls last, o.created_at desc""", DemoData.PLATFORM.id()));
    }
}
