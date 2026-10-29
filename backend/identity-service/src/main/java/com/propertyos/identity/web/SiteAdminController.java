package com.propertyos.identity.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.identity.service.SiteService;
import com.propertyos.common.rls.Tx;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform administrators: edit how the public website describes features, and work the contact enquiries. */
@RestController
@RequestMapping("/api/identity/platform/site")
@RequiresPermission("PLATFORM:VIEW")
public class SiteAdminController {
    private final SiteService site;
    private final Tx tx;

    public SiteAdminController(SiteService site, Tx tx) {
        this.site = site;
        this.tx = tx;
    }

    /** Every feature as described on the website. */
    @GetMapping("/features")
    public List<Map<String, Object>> features() {
        return tx.read(site::adminFeatures);
    }

    /** Changes a feature's title, summary or visibility on the website. */
    @PutMapping("/features/{key}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public List<Map<String, Object>> updateFeature(@PathVariable String key, @RequestBody Map<String, Object> body) {
        return tx.write(() -> site.updateFeature(key.toUpperCase(), body));
    }

    /** Contact-form enquiries, newest first. Optional {@code status}: NEW, CONTACTED or CLOSED. */
    @GetMapping("/contact-requests")
    public List<Map<String, Object>> contactRequests(@RequestParam(required = false) String status) {
        return tx.read(() -> site.contactRequests(status));
    }

    /** Marks an enquiry NEW, CONTACTED or CLOSED. */
    @PutMapping("/contact-requests/{id}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public void setStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        tx.run(() -> site.setContactStatus(id, body.get("status")));
    }
}
