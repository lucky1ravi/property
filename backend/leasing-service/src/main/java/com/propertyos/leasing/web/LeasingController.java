package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.leasing.service.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/leasing")
public class LeasingController {
    private final PortfolioService portfolio;
    private final ListingService listings;
    private final LeadService leads;
    private final ApplicationService applications;
    private final LeaseService leases;
    private final com.propertyos.common.security.SignedLinks signedLinks;

    public LeasingController(PortfolioService portfolio, ListingService listings, LeadService leads, ApplicationService applications, LeaseService leases,
                             com.propertyos.common.security.SignedLinks signedLinks) {
        this.signedLinks = signedLinks;
        this.portfolio = portfolio;
        this.listings = listings;
        this.leads = leads;
        this.applications = applications;
        this.leases = leases;
    }

    // ---- Portfolio
    @GetMapping("/properties") @RequiresPermission("PROPERTY:VIEW")
    public Object properties() { return portfolio.properties(); }

    @GetMapping("/properties/{id}") @RequiresPermission("PROPERTY:VIEW")
    public Object property(@PathVariable UUID id) { return portfolio.property(id); }

    @PostMapping("/properties") @RequiresPermission("PROPERTY:CREATE")
    public Object createProperty(@RequestBody Map<String, Object> b) { return portfolio.createProperty(b); }

    @PostMapping("/properties/{id}/units") @RequiresPermission("PROPERTY:CREATE")
    public Object createUnit(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return portfolio.createUnit(id, b); }

    @PutMapping("/units/{id}/market-rent") @RequiresPermission("PROPERTY:EDIT")
    public void marketRent(@PathVariable UUID id, @RequestBody Map<String, Object> b) { portfolio.updateUnitRent(id, Req.requiredMoney(b, "marketRent")); }

    @GetMapping("/units") @RequiresPermission("PROPERTY:VIEW")
    public Object units(@RequestParam(required = false) String status) { return portfolio.units(status); }

    @GetMapping("/owners") @RequiresPermission("PROPERTY:VIEW")
    public Object owners() { return portfolio.owners(); }

    // ---- Listings
    @GetMapping("/listings") @RequiresPermission("LISTING:VIEW")
    public Object listings(@RequestParam(required = false) String status) { return listings.list(status); }

    @GetMapping("/listings/{id}") @RequiresPermission("LISTING:VIEW")
    public Object listing(@PathVariable UUID id) { return listings.get(id); }

    @PostMapping("/listings") @RequiresPermission("LISTING:CREATE")
    public Object createListing(@RequestBody Map<String, Object> b) { return listings.create(b); }

    @PutMapping("/listings/{id}") @RequiresPermission("LISTING:EDIT")
    public Object updateListing(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return listings.update(id, b); }

    @PostMapping("/listings/{id}/ai-assist") @RequiresPermission("LISTING:EDIT")
    public Object aiAssist(@PathVariable UUID id) { return listings.aiAssist(id); }

    @PostMapping("/listings/{id}/actions/{action}") @RequiresPermission("LISTING:VIEW")
    public Object listingAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return listings.act(id, action, b == null ? null : Req.str(b, "reason"));
    }

    // ---- Leads and showings
    @GetMapping("/leads") @RequiresPermission("LISTING:VIEW")
    public Object leads() { return leads.leads(); }

    @PostMapping("/leads/{id}/respond") @RequiresPermission("LISTING:EDIT")
    public Object respond(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) { return leads.respond(id, b == null ? null : Req.str(b, "reply")); }

    @GetMapping("/showings") @RequiresPermission("SHOWING:VIEW")
    public Object showings() { return leads.showings(); }

    @PostMapping("/showings/{id}/actions/{action}") @RequiresPermission("SHOWING:EDIT")
    public Object showingAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return leads.showingAction(id, action, b == null ? null : Req.str(b, "reason"));
    }

    // ---- Applications
    @GetMapping("/applications") @RequiresPermission("APPLICATION:VIEW")
    public Object applications(@RequestParam(required = false) String status) { return applications.list(status); }

    @GetMapping("/applications/{id}") @RequiresPermission("APPLICATION:VIEW")
    public Object application(@PathVariable UUID id) { return applications.get(id); }

    @PostMapping("/applications/{id}/actions/{action}") @RequiresPermission("APPLICATION:VIEW")
    public Object applicationAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return applications.act(id, action, b == null ? null : Req.str(b, "reason"));
    }

    /** A link the leasing team can send so the applicant uploads documents without an account. */
    @PostMapping("/applications/{id}/upload-link") @RequiresPermission("APPLICATION:EDIT")
    public Object uploadLink(@PathVariable UUID id) {
        applications.get(id);   // row-level security: only applications this person can see
        return Map.of("path", "/homes/applications/" + id + "/documents?token=" + signedLinks.sign("application-upload", id));
    }

    @PostMapping("/applications/{id}/screen") @RequiresPermission("APPLICATION:EDIT")
    public Object screen(@PathVariable UUID id) { return applications.screen(id); }

    @PostMapping("/applications/{id}/lease") @RequiresPermission("LEASE:CREATE")
    public Object leaseFromApplication(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return leases.fromApplication(id, b == null ? Map.of() : b);
    }

    // ---- Leases
    @GetMapping("/leases") @RequiresPermission("LEASE:VIEW")
    public Object leases(@RequestParam(required = false) String status) { return leases.list(status); }

    @GetMapping("/leases/mine") @RequiresPermission("LEASE:VIEW")
    public Object myLease() { return leases.mine(); }

    @GetMapping("/leases/{id}") @RequiresPermission("LEASE:VIEW")
    public Object lease(@PathVariable UUID id) { return leases.get(id); }

    @PostMapping("/leases/{id}/actions/{action}") @RequiresPermission("LEASE:VIEW")
    public Object leaseAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return leases.act(id, action, b == null ? Map.of() : b);
    }

    @PostMapping("/leases/{id}/renewal") @RequiresPermission("LEASE:EDIT")
    public Object renewal(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return leases.offerRenewal(id, b == null ? Map.of() : b);
    }
}
