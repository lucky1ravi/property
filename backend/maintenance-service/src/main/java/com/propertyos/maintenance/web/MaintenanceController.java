package com.propertyos.maintenance.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.maintenance.service.MaintenanceService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * API for work orders, vendors, recurring tasks and SLA checks. Each method delegates to {@link MaintenanceService} and is guarded by {@link RequiresPermission}.
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    private final MaintenanceService svc;

    public MaintenanceController(MaintenanceService svc) {
        this.svc = svc;
    }

    /**
     * Lists work orders, optionally by status or priority.
     */
    @GetMapping("/work-orders") @RequiresPermission("WORK_ORDER:VIEW")
    public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) String priority) { return svc.list(status, priority); }

    /**
     * Returns one work order with its comments, history and allowed actions.
     */
    @GetMapping("/work-orders/{id}") @RequiresPermission("WORK_ORDER:VIEW")
    public Object get(@PathVariable UUID id) { return svc.get(id); }

    /**
     * Creates a work order. Category, priority and estimate are suggested automatically when not given.
     */
    @PostMapping("/work-orders") @RequiresPermission("WORK_ORDER:CREATE")
    public Object create(@RequestBody Map<String, Object> b) { return svc.create(b); }

    /**
     * Moves a work order through its workflow, for example assign, schedule, complete or close.
     */
    @PostMapping("/work-orders/{id}/actions/{action}") @RequiresPermission("WORK_ORDER:VIEW")
    public Object act(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return svc.act(id, action, b == null ? Map.of() : b);
    }

    /**
     * Adds a comment, optionally internal (hidden from residents).
     */
    @PostMapping("/work-orders/{id}/comments") @RequiresPermission("WORK_ORDER:VIEW")
    public Object comment(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return svc.comment(id, Req.str(b, "body"), Req.bool(b, "internal")); }

    /**
     * Lists vendors with workload and recent ratings.
     */
    @GetMapping("/vendors") @RequiresPermission("VENDOR:VIEW")
    public Object vendors() { return svc.vendors(); }

    /**
     * Returns open, emergency and SLA figures for the dashboard.
     */
    @GetMapping("/stats") @RequiresPermission("WORK_ORDER:VIEW")
    public Object stats() { return svc.stats(); }

    /**
     * Lists recurring maintenance tasks.
     */
    @GetMapping("/recurring") @RequiresPermission("WORK_ORDER:VIEW")
    public Object recurring() { return svc.recurring(); }

    /**
     * Adds a recurring maintenance task for a property.
     */
    @PostMapping("/recurring") @RequiresPermission("WORK_ORDER:EDIT")
    public Object addRecurring(@RequestBody Map<String, Object> b) { return svc.addRecurring(b); }

    /**
     * Creates work orders for recurring tasks that are due.
     */
    @PostMapping("/recurring/run") @RequiresPermission("WORK_ORDER:EDIT")
    public Object runRecurring() { return svc.runRecurring(); }

    /**
     * Flags work orders past their SLA and escalates them.
     */
    @PostMapping("/sla/check") @RequiresPermission("WORK_ORDER:EDIT")
    public Object sla() { return Map.of("escalated", svc.escalate()); }
}
