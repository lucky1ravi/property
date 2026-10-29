package com.propertyos.identity.web;

import com.propertyos.common.rls.Tx;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.identity.service.PlanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Platform administrators: create, change and delete the plans companies can be on. */
@RestController
@RequestMapping("/api/identity/platform/plans")
@RequiresPermission("PLATFORM:VIEW")
public class PlanController {
    private final PlanService plans;
    private final Tx tx;

    public PlanController(PlanService plans, Tx tx) {
        this.plans = plans;
        this.tx = tx;
    }

    /** Every plan with its price, features and how many companies are on it. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return tx.read(plans::list);
    }

    /** Creates a plan. Body: key, name, pricePerUnit, minimumMonthly, features (list of feature keys, or ["*"] for all), and optionally tagline, audience, highlight, visible. */
    @PostMapping
    @RequiresPermission("PLATFORM:CONFIGURE")
    public List<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return tx.write(() -> plans.create(body));
    }

    /** Changes any of a plan's fields. Changing its features takes effect at once for every company on it. */
    @PutMapping("/{key}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public List<Map<String, Object>> update(@PathVariable String key, @RequestBody Map<String, Object> body) {
        return tx.write(() -> plans.update(key.toUpperCase(), body));
    }

    /** Deletes a plan no company is on. */
    @DeleteMapping("/{key}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public List<Map<String, Object>> delete(@PathVariable String key) {
        return tx.write(() -> plans.delete(key.toUpperCase()));
    }
}
