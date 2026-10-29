package com.propertyos.leasing.web;

import com.propertyos.common.events.Deadlines;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.leasing.service.LeaseAlerts;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/leasing")
public class AlertController {
    private final LeaseAlerts alerts;

    public AlertController(LeaseAlerts alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return Deadlines.publicView(alerts.deadlines()); }

    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object run() { return Map.of("sent", alerts.run()); }
}
