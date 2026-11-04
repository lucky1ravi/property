package com.propertyos.leasing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignStatsTest {
    private static Map<String, Object> row(String spend, long leads, long people, long leases) {
        Map<String, Object> r = new HashMap<>();
        r.put("spend", new BigDecimal(spend));
        r.put("leads", leads);
        r.put("people", people);
        r.put("leases", leases);
        return r;
    }

    @Test
    @DisplayName("cost per lead is spend divided by leads, to the cent")
    void costPerLead() {
        Map<String, Object> r = row("500.00", 3, 3, 0);
        CampaignService.costPerLead(r);
        assertThat((BigDecimal) r.get("cost_per_lead")).isEqualByComparingTo("166.67");
    }

    @Test
    @DisplayName("no leads or no spend means no cost per lead, rather than a wrong number")
    void noCost() {
        Map<String, Object> none = row("500.00", 0, 0, 0), free = row("0", 4, 4, 1);
        CampaignService.costPerLead(none);
        CampaignService.costPerLead(free);
        assertThat(none.get("cost_per_lead")).isNull();
        assertThat(free.get("cost_per_lead")).isNull();
    }

    @Test
    @DisplayName("conversion is signed leases out of the different people who came in, as a percentage")
    void conversion() {
        Map<String, Object> r = row("100", 10, 8, 2);
        CampaignService.costPerLead(r);
        assertThat((BigDecimal) r.get("conversion")).isEqualByComparingTo("25.0");
        Map<String, Object> third = row("0", 3, 3, 1);
        CampaignService.costPerLead(third);
        assertThat((BigDecimal) third.get("conversion")).isEqualByComparingTo("33.3");
    }

    @Test
    @DisplayName("a campaign nobody has come through has no conversion yet")
    void noPeople() {
        Map<String, Object> r = row("100", 0, 0, 0);
        CampaignService.costPerLead(r);
        assertThat(r.get("conversion")).isNull();
    }
}
