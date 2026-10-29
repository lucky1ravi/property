package com.propertyos.identity.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.rls.Tx;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.identity.service.ProductDefaultsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Platform administrators: the product-wide defaults every company starts from. */
@RestController
@RequestMapping("/api/identity/platform/defaults")
@RequiresPermission("PLATFORM:VIEW")
public class ProductDefaultsController {
    private final ProductDefaultsService defaults;
    private final Tx tx;

    public ProductDefaultsController(ProductDefaultsService defaults, Tx tx) {
        this.defaults = defaults;
        this.tx = tx;
    }

    /** Current product defaults and which sections the operator has changed. */
    @GetMapping
    public Map<String, Object> view() {
        return tx.read(defaults::view);
    }

    /** Replaces one section for every company that has not set it itself. */
    @PutMapping("/{section}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> save(@PathVariable String section, @RequestBody JsonNode body) {
        return tx.write(() -> defaults.save(section, body));
    }

    /** Puts a section back to the built-in default. */
    @DeleteMapping("/{section}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> reset(@PathVariable String section) {
        return tx.write(() -> defaults.reset(section));
    }
}
