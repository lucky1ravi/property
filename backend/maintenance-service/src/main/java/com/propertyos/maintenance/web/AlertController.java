package com.propertyos.maintenance.web;

import com.propertyos.common.events.Deadlines;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.maintenance.service.VendorAlerts;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/maintenance")
public class AlertController {
    private final VendorAlerts alerts;

    public AlertController(VendorAlerts alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return Deadlines.publicView(alerts.deadlines()); }

    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object run() { return Map.of("sent", alerts.run()); }
}
