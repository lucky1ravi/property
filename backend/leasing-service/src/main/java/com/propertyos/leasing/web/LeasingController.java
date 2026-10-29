package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.leasing.service.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Staff API for the portfolio, listings, leads, showings, applications and leases. Each method delegates to one service and is guarded by {@link RequiresPermission}.
 */
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
    /**
     * Lists properties with occupancy figures.
     */
    @GetMapping("/properties") @RequiresPermission("PROPERTY:VIEW")
    public Object properties() { return portfolio.properties(); }

    /**
     * Returns one property with its units and owner.
     */
    @GetMapping("/properties/{id}") @RequiresPermission("PROPERTY:VIEW")
    public Object property(@PathVariable UUID id) { return portfolio.property(id); }

    /**
     * Creates a property.
     */
    @PostMapping("/properties") @RequiresPermission("PROPERTY:CREATE")
    public Object createProperty(@RequestBody Map<String, Object> b) { return portfolio.createProperty(b); }

    /**
     * Adds a property owner.
     */
    @PostMapping("/owners") @RequiresPermission("OWNER:CREATE")
    public Object createOwner(@RequestBody Map<String, Object> b) { return portfolio.createOwner(b); }

    /**
     * Changes an owner's details and management terms.
     */
    @PutMapping("/owners/{id}") @RequiresPermission("OWNER:EDIT")
    public void updateOwner(@PathVariable UUID id, @RequestBody Map<String, Object> b) { portfolio.updateOwner(id, b); }

    /**
     * Changes a property's details.
     */
    @PutMapping("/properties/{id}") @RequiresPermission("PROPERTY:EDIT")
    public void updateProperty(@PathVariable UUID id, @RequestBody Map<String, Object> b) { portfolio.updateProperty(id, b); }

    /**
     * Changes a unit's number, size or rent.
     */
    @PutMapping("/units/{id}") @RequiresPermission("PROPERTY:EDIT")
    public void updateUnit(@PathVariable UUID id, @RequestBody Map<String, Object> b) { portfolio.updateUnit(id, b); }

    /**
     * Archives an owner who has no properties.
     */
    @DeleteMapping("/owners/{id}") @RequiresPermission("OWNER:DELETE")
    public void archiveOwner(@PathVariable UUID id) { portfolio.archiveOwner(id); }

    /**
     * Archives a property with no occupied units or open listings.
     */
    @DeleteMapping("/properties/{id}") @RequiresPermission("PROPERTY:DELETE")
    public void archiveProperty(@PathVariable UUID id) { portfolio.archiveProperty(id); }

    /**
     * Archives a vacant unit with no open listing.
     */
    @DeleteMapping("/units/{id}") @RequiresPermission("PROPERTY:DELETE")
    public void archiveUnit(@PathVariable UUID id) { portfolio.archiveUnit(id); }

    /**
     * Lists archived owners.
     */
    @GetMapping("/owners/archived") @RequiresPermission("OWNER:DELETE")
    public Object archivedOwners() { return portfolio.archived("owner"); }

    /**
     * Lists archived properties.
     */
    @GetMapping("/properties/archived") @RequiresPermission("PROPERTY:DELETE")
    public Object archivedProperties() { return portfolio.archived("property"); }

    /**
     * Lists archived units.
     */
    @GetMapping("/units/archived") @RequiresPermission("PROPERTY:DELETE")
    public Object archivedUnits() { return portfolio.archived("unit"); }

    /**
     * Brings an archived owner back.
     */
    @PostMapping("/owners/{id}/restore") @RequiresPermission("OWNER:DELETE")
    public void restoreOwner(@PathVariable UUID id) { portfolio.restore("owner", id); }

    /**
     * Brings an archived property back, with its units.
     */
    @PostMapping("/properties/{id}/restore") @RequiresPermission("PROPERTY:DELETE")
    public void restoreProperty(@PathVariable UUID id) { portfolio.restore("property", id); }

    /**
     * Brings an archived unit back.
     */
    @PostMapping("/units/{id}/restore") @RequiresPermission("PROPERTY:DELETE")
    public void restoreUnit(@PathVariable UUID id) { portfolio.restore("unit", id); }

    /**
     * Lists residents (people with a lease) for linking a sign-in to them.
     */
    @GetMapping("/residents") @RequiresPermission("USER:VIEW")
    public Object residents() { return portfolio.residents(); }

    /**
     * Adds a unit to a property.
     */
    @PostMapping("/properties/{id}/units") @RequiresPermission("PROPERTY:CREATE")
    public Object createUnit(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return portfolio.createUnit(id, b); }

    /**
     * Changes a unit's market rent.
     */
    @PutMapping("/units/{id}/market-rent") @RequiresPermission("PROPERTY:EDIT")
    public void marketRent(@PathVariable UUID id, @RequestBody Map<String, Object> b) { portfolio.updateUnitRent(id, Req.requiredMoney(b, "marketRent")); }

    /**
     * Lists units, optionally only those with a status such as VACANT.
     */
    @GetMapping("/units") @RequiresPermission("PROPERTY:VIEW")
    public Object units(@RequestParam(required = false) String status) { return portfolio.units(status); }

    /**
     * Lists owners with their properties and fee terms.
     */
    @GetMapping("/owners") @RequiresPermission("OWNER:VIEW")
    public Object owners() { return portfolio.owners(); }

    // ---- Listings
    /**
     * Lists listings, optionally filtered by status.
     */
    @GetMapping("/listings") @RequiresPermission("LISTING:VIEW")
    public Object listings(@RequestParam(required = false) String status) { return listings.list(status); }

    /**
     * Returns one listing.
     */
    @GetMapping("/listings/{id}") @RequiresPermission("LISTING:VIEW")
    public Object listing(@PathVariable UUID id) { return listings.get(id); }

    /**
     * Creates a draft listing for a unit.
     */
    @DeleteMapping("/listings/{id}") @RequiresPermission("LISTING:DELETE")
    public void deleteListing(@PathVariable UUID id) { listings.deleteDraft(id); }

    /**
     * Creates a draft listing for a vacant unit.
     */
    @PostMapping("/listings") @RequiresPermission("LISTING:CREATE")
    public Object createListing(@RequestBody Map<String, Object> b) { return listings.create(b); }

    /**
     * Edits a draft listing.
     */
    @PutMapping("/listings/{id}") @RequiresPermission("LISTING:EDIT")
    public Object updateListing(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return listings.update(id, b); }

    /**
     * Suggests a rent from comparable units (reduced after 30 days vacant) and drafts listing copy with AI, falling back to rules when no model is available.
     */
    @PostMapping("/listings/{id}/ai-assist") @RequiresPermission("LISTING:EDIT")
    public Object aiAssist(@PathVariable UUID id) { return listings.aiAssist(id); }

    /**
     * Moves a listing through its workflow, for example submit, approve or unpublish.
     */
    @PostMapping("/listings/{id}/actions/{action}") @RequiresPermission("LISTING:VIEW")
    public Object listingAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return listings.act(id, action, b == null ? null : Req.str(b, "reason"));
    }

    // ---- Leads and showings
    /**
     * Lists enquiries from the public site.
     */
    @GetMapping("/leads") @RequiresPermission("LISTING:VIEW")
    public Object leads() { return leads.leads(); }

    /**
     * Marks a lead as answered, using the given reply or the AI-drafted one.
     */
    @PostMapping("/leads/{id}/respond") @RequiresPermission("LISTING:EDIT")
    public Object respond(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) { return leads.respond(id, b == null ? null : Req.str(b, "reply")); }

    /**
     * Lists showings.
     */
    @GetMapping("/showings") @RequiresPermission("SHOWING:VIEW")
    public Object showings() { return leads.showings(); }

    /**
     * Moves a showing through its workflow, for example confirm or cancel.
     */
    @PostMapping("/showings/{id}/actions/{action}") @RequiresPermission("SHOWING:EDIT")
    public Object showingAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return leads.showingAction(id, action, b == null ? null : Req.str(b, "reason"));
    }

    // ---- Applications
    /**
     * Lists applications, optionally filtered by status.
     */
    @GetMapping("/applications") @RequiresPermission("APPLICATION:VIEW")
    public Object applications(@RequestParam(required = false) String status) { return applications.list(status); }

    /**
     * Returns one application with its screening results and allowed actions.
     */
    @GetMapping("/applications/{id}") @RequiresPermission("APPLICATION:VIEW")
    public Object application(@PathVariable UUID id) { return applications.get(id); }

    /**
     * Moves an application through its workflow, for example approve or reject.
     */
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

    /**
     * Runs screening on an application.
     */
    @PostMapping("/applications/{id}/screen") @RequiresPermission("APPLICATION:EDIT")
    public Object screen(@PathVariable UUID id) { return applications.screen(id); }

    /**
     * Creates a lease from an approved application.
     */
    @PostMapping("/applications/{id}/lease") @RequiresPermission("LEASE:CREATE")
    public Object leaseFromApplication(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return leases.fromApplication(id, b == null ? Map.of() : b);
    }

    // ---- Leases
    /**
     * Lists leases, optionally filtered by status.
     */
    @GetMapping("/leases") @RequiresPermission("LEASE:VIEW")
    public Object leases(@RequestParam(required = false) String status) { return leases.list(status); }

    /**
     * Returns the signed-in resident's own lease.
     */
    @GetMapping("/leases/mine") @RequiresPermission("LEASE:VIEW")
    public Object myLease() { return leases.mine(); }

    /**
     * Returns one lease with its allowed actions.
     */
    @GetMapping("/leases/{id}") @RequiresPermission("LEASE:VIEW")
    public Object lease(@PathVariable UUID id) { return leases.get(id); }

    /**
     * Moves a lease through its workflow, for example send for signature or terminate.
     */
    @PostMapping("/leases/{id}/actions/{action}") @RequiresPermission("LEASE:VIEW")
    public Object leaseAction(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return leases.act(id, action, b == null ? Map.of() : b);
    }

    /**
     * Offers a renewal on a lease.
     */
    @PostMapping("/leases/{id}/renewal") @RequiresPermission("LEASE:EDIT")
    public Object renewal(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return leases.offerRenewal(id, b == null ? Map.of() : b);
    }
}
