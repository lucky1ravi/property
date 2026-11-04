package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import com.propertyos.leasing.service.BoostAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform administrators: listing boosts across all companies, free grants, cancelling, the prices agreed with individual companies and promo codes.
 * Standard prices, plan discounts, the ranking bonus and the page cap are in the product defaults ("Listing boosts"), not here.
 */
@RestController
@RequestMapping("/api/leasing/platform/boosts")
public class BoostAdminController {
    private final BoostAdminService admin;

    public BoostAdminController(BoostAdminService admin) {
        this.admin = admin;
    }

    private static String me() {
        return UserContextHolder.require().name();
    }

    /** Boosts running, waiting for payment, granted for free and ending within 3 days. */
    @GetMapping("/overview") @RequiresPermission("PLATFORM:VIEW")
    public Map<String, Object> overview() {
        return admin.overview();
    }

    /** Every company's boosts, newest first (paged: page, size, sort, dir, q). */
    @GetMapping @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> list() {
        return admin.list();
    }

    /** Live listings that could be given a free boost, matching the search text (query: q). */
    @GetMapping("/eligible") @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> eligible(@RequestParam(required = false) String q) {
        return admin.eligible(q);
    }

    /** Ends a boost now. Body: reason (the company is told). An unpaid boost's invoice is voided; a paid one is not refunded automatically. */
    @PostMapping("/{id}/cancel") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return admin.cancel(id, Req.str(body, "reason"), me());
    }

    /** Gives a live listing a free boost starting now. Body: listingId, days (1 to 90), reason. */
    @PostMapping("/grant") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> grant(@RequestBody Map<String, Object> body) {
        Integer days = Req.integer(body, "days");
        return admin.grant(Req.requiredUuid(body, "listingId"), days == null ? 0 : days, Req.str(body, "reason"), me());
    }

    /** Prices agreed with individual companies. */
    @GetMapping("/prices") @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> prices() {
        return admin.prices();
    }

    /** Sets what one company pays for one period. Body: orgId, days, price, optional note. */
    @PutMapping("/prices") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> setPrice(@RequestBody Map<String, Object> body) {
        Integer days = Req.integer(body, "days");
        if (days == null) throw ApiException.badRequest("Choose the period in days.");
        return admin.setPrice(Req.requiredUuid(body, "orgId"), days, Req.required(body, "price"), Req.str(body, "note"), me());
    }

    /** Takes a company back to the standard price for a period (query: orgId, days). */
    @DeleteMapping("/prices") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> removePrice(@RequestParam UUID orgId, @RequestParam int days) {
        return admin.removePrice(orgId, days, me());
    }

    /** Promo codes with their use counts. */
    @GetMapping("/promos") @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> promos() {
        return admin.promos();
    }

    /** Makes a promo code. Body: code, percentOff or amountOff, optional orgId, validFrom, validUntil, maxUses, note. */
    @PostMapping("/promos") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> createPromo(@RequestBody Map<String, Object> body) {
        return admin.createPromo(body, me());
    }

    /** Switches a promo code on or off. Body: active. */
    @PutMapping("/promos/{code}") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> promoActive(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return admin.setPromoActive(code, Boolean.TRUE.equals(body.get("active")), me());
    }
}
