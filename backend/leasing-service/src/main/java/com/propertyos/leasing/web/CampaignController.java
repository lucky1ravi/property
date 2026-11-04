package com.propertyos.leasing.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.leasing.service.CampaignService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff: a company's marketing campaigns. Leads and applications that arrive through a campaign are tagged with it. Needs the CAMPAIGNS plan feature and
 * the CAMPAIGN permissions. A campaign's signing secret appears only in the response that makes or rotates it.
 */
@RestController
@RequestMapping("/api/leasing/campaigns")
public class CampaignController {
    private final CampaignService campaigns;

    public CampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    /** The company's campaigns with leads, applications and cost per lead, and the keys that identify them (never the secret). */
    @GetMapping @RequiresPermission("CAMPAIGN:VIEW")
    public List<Map<String, Object>> list() {
        return campaigns.list();
    }

    /** Leads over time for charts (query: campaignId for one campaign, days, bucket=day or week). Without a campaign id, the busiest campaigns side by side. */
    @GetMapping("/series") @RequiresPermission("CAMPAIGN:VIEW")
    public Map<String, Object> series(@RequestParam(required = false) UUID campaignId, @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "day") String bucket) {
        return campaigns.series(campaignId, days, "week".equalsIgnoreCase(bucket));
    }

    /** One campaign with its numbers and its recent activity (never the secret). */
    @GetMapping("/{id}") @RequiresPermission("CAMPAIGN:VIEW")
    public Map<String, Object> get(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    /** Sends a realistic sample lead through the campaign's own signed inbound address, for demonstrations. The secret stays on the server. */
    @PostMapping("/{id}/test-lead") @RequiresPermission("CAMPAIGN:EDIT")
    public Map<String, Object> testLead(@PathVariable UUID id) {
        return campaigns.sendTestLead(id);
    }

    /** Makes a campaign. Body: name, channel (FACEBOOK_ADS, GOOGLE_ADS, ZILLOW, APARTMENTS, EMAIL, OTHER), optional spend, startsOn, endsOn. Returns the secret once. */
    @PostMapping @RequiresPermission("CAMPAIGN:CREATE")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return campaigns.create(body);
    }

    /** Changes a campaign's name, channel, spend or dates. */
    @PutMapping("/{id}") @RequiresPermission("CAMPAIGN:EDIT")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return campaigns.update(id, body);
    }

    /** Pauses a campaign: its inbound address and tracking link stop bringing in new leads. */
    @PostMapping("/{id}/pause") @RequiresPermission("CAMPAIGN:EDIT")
    public Map<String, Object> pause(@PathVariable UUID id) {
        return campaigns.setPaused(id, true);
    }

    /** Resumes a paused campaign. */
    @PostMapping("/{id}/resume") @RequiresPermission("CAMPAIGN:EDIT")
    public Map<String, Object> resume(@PathVariable UUID id) {
        return campaigns.setPaused(id, false);
    }

    /** Replaces the signing secret (the old one stops working at once). Body: newAddress (true also changes the inbound address). Returns the new secret once. */
    @PostMapping("/{id}/rotate-key") @RequiresPermission("CAMPAIGN:EDIT")
    public Map<String, Object> rotate(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return campaigns.rotate(id, body != null && Boolean.TRUE.equals(body.get("newAddress")));
    }
}
