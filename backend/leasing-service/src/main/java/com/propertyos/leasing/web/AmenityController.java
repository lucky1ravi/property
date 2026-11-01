package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.leasing.service.AmenityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The amenity catalogue. A company picks from the platform's list and its own, and adds and manages its own; the platform
 * administrator manages the platform-wide list under {@code /api/leasing/platform/amenities}.
 */
@RestController
public class AmenityController {
    private final AmenityService amenities;

    public AmenityController(AmenityService amenities) {
        this.amenities = amenities;
    }

    /** Everything the signed-in company can pick: the platform's amenities and its own, with their icons. */
    @GetMapping("/api/leasing/amenities") @RequiresPermission("PROPERTY:VIEW")
    public List<Map<String, Object>> available() {
        return amenities.available();
    }

    /** The company's own amenities (the ones it can change). */
    @GetMapping("/api/leasing/amenities/mine") @RequiresPermission("PROPERTY:VIEW")
    public List<Map<String, Object>> mine() {
        return amenities.managed(false);
    }

    @PostMapping("/api/leasing/amenities") @RequiresPermission("PROPERTY:EDIT")
    public Map<String, Object> add(@RequestBody Map<String, Object> body) {
        return amenities.create(body, false);
    }

    @PutMapping("/api/leasing/amenities/{id}") @RequiresPermission("PROPERTY:EDIT")
    public Map<String, Object> change(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return amenities.update(id, body, false);
    }

    @DeleteMapping("/api/leasing/amenities/{id}") @RequiresPermission("PROPERTY:EDIT")
    public Map<String, Object> remove(@PathVariable UUID id) {
        amenities.delete(id, false);
        return Map.of("removed", true);
    }

    // ---- the platform-wide list --------------------------------------------------------------------------

    @GetMapping("/api/leasing/platform/amenities") @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> platformList() {
        return amenities.managed(true);
    }

    @PostMapping("/api/leasing/platform/amenities") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> platformAdd(@RequestBody Map<String, Object> body) {
        return amenities.create(body, true);
    }

    @PutMapping("/api/leasing/platform/amenities/{id}") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> platformChange(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return amenities.update(id, body, true);
    }

    @DeleteMapping("/api/leasing/platform/amenities/{id}") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> platformRemove(@PathVariable UUID id) {
        amenities.delete(id, true);
        return Map.of("removed", true);
    }
}
