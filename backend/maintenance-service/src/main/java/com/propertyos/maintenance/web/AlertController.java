package com.propertyos.maintenance.web;

import com.propertyos.common.events.Deadlines;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.maintenance.service.VendorAlerts;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Vendor-insurance deadlines and the manual trigger for the alert run.
 */
@RestController
@RequestMapping("/api/maintenance")
public class AlertController {
    private final VendorAlerts alerts;

    public AlertController(VendorAlerts alerts) {
        this.alerts = alerts;
    }

    /**
     * Lists vendor insurance expiries inside the alert horizon.
     */
    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return Deadlines.publicView(alerts.deadlines()); }

    /**
     * Sends due vendor alerts now instead of waiting for the daily timer.
     */
    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object run() { return Map.of("sent", alerts.run()); }
}
