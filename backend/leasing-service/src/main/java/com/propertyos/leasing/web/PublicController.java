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

    @GetMapping("/listings")
    public Object listings(@RequestParam(required = false) String city, @RequestParam(required = false) Integer beds,
                           @RequestParam(required = false) Integer maxRent) {
        return service.listings(city, beds, maxRent);
    }

    @GetMapping("/listings/{id}")
    public Object listing(@PathVariable UUID id) { return service.listing(id); }

    @PostMapping("/listings/{id}/enquiries")
    public Object enquire(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return service.enquire(id, b); }

    @PostMapping("/listings/{id}/showings")
    public Object showing(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return service.requestShowing(id, b); }

    @PostMapping("/listings/{id}/applications")
    public Object apply(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return service.apply(id, b); }
}
