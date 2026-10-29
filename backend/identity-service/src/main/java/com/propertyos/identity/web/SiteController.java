package com.propertyos.identity.web;

import com.propertyos.identity.service.SiteService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Public website content and the contact form. Open to everyone and rate limited per address at the gateway. */
@RestController
@RequestMapping("/api/identity/public")
public class SiteController {
    private final SiteService site;

    public SiteController(SiteService site) {
        this.site = site;
    }

    /** Plans with prices and the features each includes, plus the feature list, for the marketing pages. */
    @GetMapping("/site")
    public Map<String, Object> site() {
        return site.publicSite();
    }

    /** Contact form. Body: name, email, message, and optionally company, phone, units, topic. */
    @PostMapping("/contact")
    public Map<String, Object> contact(@RequestBody Map<String, Object> body) {
        site.submitContact(body);
        return Map.of("received", true);
    }
}
