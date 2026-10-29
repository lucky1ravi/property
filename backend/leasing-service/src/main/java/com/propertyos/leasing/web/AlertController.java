package com.propertyos.leasing.web;

import com.propertyos.common.events.Deadlines;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.leasing.service.LeaseAlerts;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Lease-expiry deadlines and the manual trigger for the alert run.
 */
@RestController
@RequestMapping("/api/leasing")
public class AlertController {
    private final LeaseAlerts alerts;

    public AlertController(LeaseAlerts alerts) {
        this.alerts = alerts;
    }

    /**
     * Lists upcoming lease deadlines (expiry and renewal notices).
     */
    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return Deadlines.publicView(alerts.deadlines()); }

    /**
     * Sends due lease alerts now instead of waiting for the daily timer.
     */
    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object run() { return Map.of("sent", alerts.run()); }
}
