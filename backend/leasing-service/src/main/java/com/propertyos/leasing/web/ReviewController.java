package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.leasing.service.ReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Residents' ratings of the property and unit they live in. */
@RestController
@RequestMapping("/api/leasing/reviews")
public class ReviewController {
    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /** A resident's home as they can rate it, with the rating they already gave. */
    @GetMapping("/mine") @RequiresPermission("LEASE:VIEW")
    public Map<String, Object> mine() {
        return reviews.mine();
    }

    /** Rates the property or the unit, or changes the rating. Body: subjectType, subjectId, stars, optional comment. */
    @PutMapping @RequiresPermission("LEASE:VIEW")
    public Map<String, Object> rate(@RequestBody Map<String, Object> body) {
        return reviews.rate(body);
    }

    /** Takes back the resident's own rating. */
    @DeleteMapping("/{type}/{id}") @RequiresPermission("LEASE:VIEW")
    public Map<String, Object> withdraw(@PathVariable String type, @PathVariable UUID id) {
        reviews.withdraw(type, id);
        return Map.of("withdrawn", true);
    }

    /** Staff: the company's public reply to a review. Body: reply (empty removes it). */
    @PutMapping("/{id}/reply") @RequiresPermission("REVIEW:EDIT")
    public Map<String, Object> reply(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return reviews.reply(id, com.propertyos.common.web.Req.str(body, "reply"));
    }

    /** Staff: shows or hides one comment on the public page whatever the AI scored it, or hands the decision back to the AI. Body: decision (SHOW, HIDE or CLEAR), reason. */
    @PutMapping("/{id}/moderation") @RequiresPermission("REVIEW:APPROVE")
    public Map<String, Object> moderate(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return reviews.moderate(id, com.propertyos.common.web.Req.str(body, "decision"), com.propertyos.common.web.Req.str(body, "reason"));
    }

    /** The ratings of a property or unit that the signed-in person may see. */
    @GetMapping("/{type}/{id}") @RequiresPermission("LEASE:VIEW")
    public Map<String, Object> list(@PathVariable String type, @PathVariable UUID id) {
        return reviews.reviews(type, id);
    }
}
