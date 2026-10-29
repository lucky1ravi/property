package com.propertyos.ai.web;

import com.propertyos.ai.service.*;
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
    private final LeaseAbstractTool leaseAbstract;
    private final AnomalyScanTool anomalyScan;
    private final StatementNarrativeTool statementNarrative;
    private final ResidentOutlookTool residentOutlook;
    private final PhotoTriageTool photoTriage;
    private final FairHousingTool fairHousing;

    public StudioController(LeaseAbstractTool leaseAbstract, AnomalyScanTool anomalyScan, StatementNarrativeTool statementNarrative,
                            ResidentOutlookTool residentOutlook, PhotoTriageTool photoTriage, FairHousingTool fairHousing) {
        this.leaseAbstract = leaseAbstract;
        this.anomalyScan = anomalyScan;
        this.statementNarrative = statementNarrative;
        this.residentOutlook = residentOutlook;
        this.photoTriage = photoTriage;
        this.fairHousing = fairHousing;
    }

    /**
     * Summarises a lease's key terms.
     */
    @PostMapping("/lease-abstract/{id}") @RequiresPermission("LEASE:VIEW")
    public Object lease(@PathVariable String id) { return leaseAbstract.summarise(Upstream.currentBearer(), id); }

    /**
     * Scans invoices, statements and leases for unusual items.
     */
    @GetMapping("/anomalies") @RequiresPermission("INVOICE:VIEW")
    public Object anomalies() { return anomalyScan.scan(Upstream.currentBearer()); }

    /**
     * Drafts a plain-language narrative for an owner statement.
     */
    @PostMapping("/statement-narrative/{id}") @RequiresPermission("OWNER_STATEMENT:VIEW")
    public Object statement(@PathVariable String id) { return statementNarrative.draft(Upstream.currentBearer(), id); }

    /**
     * Estimates renewal and risk outlook for active residents.
     */
    @GetMapping("/resident-outlook") @RequiresPermission("LEASE:VIEW")
    public Object outlook() { return residentOutlook.outlook(Upstream.currentBearer()); }

    /**
     * Analyses a maintenance photo (JPG, PNG, WebP or GIF up to 8 MB) and suggests category, priority and next steps.
     */
    @PostMapping(value = "/photo-triage", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @RequiresPermission("WORK_ORDER:VIEW")
    public Object photo(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String note) throws java.io.IOException {
        String mime = file.getContentType() == null ? "" : file.getContentType();
        if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(mime)) throw ApiException.badRequest("Use a JPG, PNG, WebP or GIF photo.");
        if (file.getSize() > 8L * 1024 * 1024) throw ApiException.badRequest("The photo is larger than 8 MB.");
        return photoTriage.triage(file.getBytes(), mime, note);
    }

    /**
     * Checks listing or message text for fair-housing risk.
     */
    @PostMapping("/fair-housing") @RequiresPermission("LISTING:VIEW")
    public Object fair(@RequestBody Map<String, Object> b) { return fairHousing.check(Req.str(b, "text")); }
}
