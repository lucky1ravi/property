package com.propertyos.leasing.service;

import com.propertyos.common.rls.Db;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Finds the campaign a website visitor came from, by the tracking code in the link they followed. A code only counts for a campaign of the same
 * company as the home they are enquiring about, and only while the campaign is active and inside its dates. Anything else is silently ignored,
 * so a stale or forged code never stops an enquiry from going through.
 */
@Component
public class CampaignLookup {
    private final Db db;

    public CampaignLookup(Db db) {
        this.db = db;
    }

    /** The campaign for this tracking code and company, or null. Runs in the caller's transaction. */
    public UUID forTrackingCode(String code, UUID org) {
        if (code == null || org == null) return null;
        String c = code.trim();
        if (c.isEmpty() || c.length() > 16) return null;
        return db.find("""
                select id from leasing.campaign
                where tracking_code = ? and org_id = ? and status = 'ACTIVE'
                  and (starts_on is null or starts_on <= current_date) and (ends_on is null or ends_on >= current_date)""", c, org)
                .map(r -> (UUID) r.get("id")).orElse(null);
    }
}
