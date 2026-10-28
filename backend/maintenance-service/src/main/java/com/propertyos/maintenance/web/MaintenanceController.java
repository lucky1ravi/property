package com.propertyos.maintenance.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.maintenance.service.MaintenanceService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    private final MaintenanceService svc;

    public MaintenanceController(MaintenanceService svc) {
        this.svc = svc;
    }

    @GetMapping("/work-orders") @RequiresPermission("WORK_ORDER:VIEW")
    public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) String priority) { return svc.list(status, priority); }

    @GetMapping("/work-orders/{id}") @RequiresPermission("WORK_ORDER:VIEW")
    public Object get(@PathVariable UUID id) { return svc.get(id); }

    @PostMapping("/work-orders") @RequiresPermission("WORK_ORDER:CREATE")
    public Object create(@RequestBody Map<String, Object> b) { return svc.create(b); }

    @PostMapping("/work-orders/{id}/actions/{action}") @RequiresPermission("WORK_ORDER:VIEW")
    public Object act(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return svc.act(id, action, b == null ? Map.of() : b);
    }

    @PostMapping("/work-orders/{id}/comments") @RequiresPermission("WORK_ORDER:VIEW")
    public Object comment(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return svc.comment(id, Req.str(b, "body"), Req.bool(b, "internal")); }

    @GetMapping("/vendors") @RequiresPermission("VENDOR:VIEW")
    public Object vendors() { return svc.vendors(); }

    @GetMapping("/stats") @RequiresPermission("WORK_ORDER:VIEW")
    public Object stats() { return svc.stats(); }

    @GetMapping("/recurring") @RequiresPermission("WORK_ORDER:VIEW")
    public Object recurring() { return svc.recurring(); }

    @PostMapping("/recurring") @RequiresPermission("WORK_ORDER:EDIT")
    public Object addRecurring(@RequestBody Map<String, Object> b) { return svc.addRecurring(b); }

    @PostMapping("/recurring/run") @RequiresPermission("WORK_ORDER:EDIT")
    public Object runRecurring() { return svc.runRecurring(); }

    @PostMapping("/sla/check") @RequiresPermission("WORK_ORDER:EDIT")
    public Object sla() { return Map.of("escalated", svc.escalate()); }
}
