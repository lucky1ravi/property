package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.leasing.service.BoostService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Paid boosts for live listings: the periods on offer, buying one, and a listing's boost history. */
@RestController
@RequestMapping("/api/leasing")
public class BoostController {
    private final BoostService boosts;

    public BoostController(BoostService boosts) {
        this.boosts = boosts;
    }

    /** The boost periods and prices on offer, and what a boost does. */
    @GetMapping("/boosts/options") @RequiresPermission("BOOST:VIEW")
    public Map<String, Object> options() {
        return boosts.options();
    }

    /** What a period would cost this company, with an optional promo code (query: days, code). Nothing is bought. */
    @GetMapping("/boosts/quote") @RequiresPermission("BOOST:VIEW")
    public Map<String, Object> quote(@RequestParam int days, @RequestParam(required = false) String code) {
        return boosts.quote(days, code);
    }

    /** A listing's boosts, newest first: waiting for payment, running, or over. */
    @GetMapping("/listings/{id}/boosts") @RequiresPermission("BOOST:VIEW")
    public List<Map<String, Object>> forListing(@PathVariable UUID id) {
        return boosts.forListing(id);
    }

    /**
     * Buys a boost (needs BOOST:CREATE, held by company administrators by default) for a live listing. Raises an invoice, which is paid from Subscription; the boost starts when it is paid.
     * Body: days (one of the periods on offer), optional promoCode.
     */
    @PostMapping("/listings/{id}/boosts") @RequiresPermission("BOOST:CREATE")
    public Map<String, Object> boost(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Integer days = Req.integer(body, "days");
        return boosts.request(id, days == null ? 0 : days, Req.str(body, "promoCode"));
    }
}
