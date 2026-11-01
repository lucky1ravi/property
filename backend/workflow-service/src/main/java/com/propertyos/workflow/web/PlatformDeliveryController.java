package com.propertyos.workflow.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.workflow.service.PlatformDeliveries;
import com.propertyos.common.web.Req;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** The operator's delivery log: every email and text the platform sent, for every company. Platform administrators only. */
@RestController
public class PlatformDeliveryController {
    private final PlatformDeliveries deliveries;

    public PlatformDeliveryController(PlatformDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    /** Deliveries across all companies, paged: {@code page}, {@code size}, {@code sort}, {@code q} and {@code f.<column>}. */
    @GetMapping("/api/workflow/platform/deliveries") @RequiresPermission("PLATFORM:VIEW")
    public Object list(@RequestParam(required = false) UUID orgId) {
        return deliveries.list(orgId);
    }

    /**
     * One delivery in full (whole recipient and subject, never the message text), for support work. Body: {@code reason}, required, kept in the
     * audit log of the company the message was sent for.
     */
    @PostMapping("/api/workflow/platform/deliveries/{id}/reveal") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> reveal(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return deliveries.reveal(id, Req.str(body, "reason"));
    }

    /** Totals for the last 7 days. */
    @GetMapping("/api/workflow/platform/deliveries/summary") @RequiresPermission("PLATFORM:VIEW")
    public Object summary() {
        return deliveries.summary();
    }
}
