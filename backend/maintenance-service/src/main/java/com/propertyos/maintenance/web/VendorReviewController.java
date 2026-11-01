package com.propertyos.maintenance.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.maintenance.service.VendorReviews;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Ratings and comments about vendors. */
@RestController
@RequestMapping("/api/maintenance/vendors/{id}/reviews")
public class VendorReviewController {
    private final VendorReviews reviews;

    public VendorReviewController(VendorReviews reviews) {
        this.reviews = reviews;
    }

    /** The ratings this person may see, with the average and how the stars are spread. */
    @GetMapping @RequiresPermission("WORK_ORDER:VIEW")
    public Map<String, Object> list(@PathVariable UUID id) {
        return reviews.reviews(id);
    }

    /** Rates a vendor, or changes the rating. Body: stars (1 to 5), optional comment, and workOrderId (required for a resident). */
    @PostMapping @RequiresPermission("WORK_ORDER:VIEW")
    public Map<String, Object> rate(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return reviews.rate(id, body);
    }
}
