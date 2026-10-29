package com.propertyos.leasing.web;

import com.propertyos.leasing.service.PublicService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Open endpoints for the public homes site. */
@RestController
@RequestMapping("/api/leasing/public")
public class PublicController {
    private final PublicService service;

    public PublicController(PublicService service) {
        this.service = service;
    }

    /**
     * Public: lists live rentals, filtered by city, bedrooms and maximum rent.
     */
    @GetMapping("/listings")
    public Object listings(@RequestParam(required = false) String city, @RequestParam(required = false) Integer beds,
                           @RequestParam(required = false) Integer maxRent) {
        return service.listings(city, beds, maxRent);
    }

    /**
     * Public: returns one live rental.
     */
    @GetMapping("/listings/{id}")
    public Object listing(@PathVariable UUID id) { return service.listing(id); }

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
