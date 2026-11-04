package com.propertyos.leasing.web;

import com.propertyos.leasing.service.PublicCatalog;
import com.propertyos.leasing.service.PublicService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Open endpoints for the public homes site. */
@RestController
@RequestMapping("/api/leasing/public")
public class PublicController {
    private final PublicService service;
    private final PublicCatalog catalog;
    private final com.propertyos.leasing.service.CampaignInbound inbound;

    public PublicController(PublicService service, PublicCatalog catalog, com.propertyos.leasing.service.CampaignInbound inbound) {
        this.service = service;
        this.catalog = catalog;
        this.inbound = inbound;
    }

    /**
     * Public: lists live rentals one home at a time. Filters: keyword, city, state, type, beds, baths, minRent, maxRent, minSqft, maxSqft,
     * availableBy and amenities (comma separated, all required). Pass page, size, sort and dir to get a page.
     */
    @GetMapping("/listings")
    public Object listings(@RequestParam Map<String, String> q) { return catalog.homes(q); }

    /**
     * Public: lists properties that have matching homes available, each with those homes inside it. Same filters and paging as the homes list.
     */
    @GetMapping("/properties")
    public Object properties(@RequestParam Map<String, String> q) { return catalog.properties(q); }

    /**
     * Public: the choices worth offering in the filters (cities, types, amenities with icons, rent and size ranges).
     */
    @GetMapping("/facets")
    public Object facets() { return catalog.facets(); }

    /**
     * Public: returns one live rental.
     */
    @GetMapping("/listings/{id}")
    public Object listing(@PathVariable UUID id) { return service.listing(id); }

    /**
     * Public: the resident ratings and written comments for a rental's property and unit.
     */
    @GetMapping("/listings/{id}/reviews")
    public Object reviews(@PathVariable UUID id) { return service.reviews(id); }

    /**
     * Public, signed: a campaign's inbound address. An ad platform or form tool posts a lead or an application as JSON and signs the exact body with the
     * campaign's secret (HMAC-SHA256, hex, in the X-Signature header). Body: type (lead or application), name, email, phone, listingId or unitId, message;
     * an application also needs consent: true. Unknown key is 404, a missing or wrong signature 401. The same person asking about the same home twice
     * is reported as a duplicate, not entered again.
     */
    @PostMapping("/inbound/{campaignKey}")
    public Object inbound(@PathVariable String campaignKey, @RequestHeader(value = "X-Signature", required = false) String signature, @RequestBody byte[] body) {
        return inbound.receive(campaignKey, signature, body);
    }

    /**
     * Public: records an enquiry about a rental as a lead.
     */
    @PostMapping("/listings/{id}/enquiries")
    public Object enquire(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return service.enquire(id, b); }

    /**
     * Public: requests a showing of a rental.
     */
    @PostMapping("/listings/{id}/showings")
    public Object showing(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return service.requestShowing(id, b); }

    /**
     * Public: submits a rental application.
     */
    @PostMapping("/listings/{id}/applications")
    public Object apply(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return service.apply(id, b); }
}
