package com.propertyos.ai.web;

import com.propertyos.ai.service.Studio;
import com.propertyos.ai.service.Upstream;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/** Task-specific AI tools for staff. Every call uses the signed-in person's own access to read data. */
@RestController
@RequestMapping("/api/ai/studio")
@RequiresPermission("AI_ASSISTANT:VIEW")
public class StudioController {
    private final Studio studio;

    public StudioController(Studio studio) {
        this.studio = studio;
    }

    @PostMapping("/lease-abstract/{id}") @RequiresPermission("LEASE:VIEW")
    public Object lease(@PathVariable String id) { return studio.leaseAbstract(Upstream.currentBearer(), id); }

    @GetMapping("/anomalies") @RequiresPermission("INVOICE:VIEW")
    public Object anomalies() { return studio.anomalies(Upstream.currentBearer()); }

    @PostMapping("/statement-narrative/{id}") @RequiresPermission("OWNER_STATEMENT:VIEW")
    public Object statement(@PathVariable String id) { return studio.statementNarrative(Upstream.currentBearer(), id); }

    @GetMapping("/resident-outlook") @RequiresPermission("LEASE:VIEW")
    public Object outlook() { return studio.residentOutlook(Upstream.currentBearer()); }

    @PostMapping(value = "/photo-triage", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @RequiresPermission("WORK_ORDER:VIEW")
    public Object photo(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String note) throws java.io.IOException {
        String mime = file.getContentType() == null ? "" : file.getContentType();
        if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(mime)) throw ApiException.badRequest("Use a JPG, PNG, WebP or GIF photo.");
        if (file.getSize() > 8L * 1024 * 1024) throw ApiException.badRequest("The photo is larger than 8 MB.");
        return studio.photoTriage(file.getBytes(), mime, note);
    }

    @PostMapping("/fair-housing") @RequiresPermission("LISTING:VIEW")
    public Object fair(@RequestBody Map<String, Object> b) { return studio.fairHousing(Req.str(b, "text")); }
}
