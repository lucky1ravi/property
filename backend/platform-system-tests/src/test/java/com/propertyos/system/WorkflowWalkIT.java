package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs last among the scenario tests because it ends leases and closes work orders. Moves every demo lease, application, listing, showing and work order through its workflow, choosing a different path
 * for each record. Covers the workflow engine, the state machine and the side effects of each transition (notifications,
 * events, unit status, invoices) in one pass. No step may fail with a server error.
 */
class WorkflowWalkIT {

    @Test
    void everyRecordCanBeDrivenThroughItsWorkflow() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        ApiClient leasing = Flow.leasing();
        for (String collection : new String[]{"leases", "applications", "listings", "showings"}) {
            JsonNode rows = Flow.rows(leasing.get(admin, "/api/leasing/" + collection));
            for (int i = 0; i < rows.size(); i++)
                Flow.walk(soft, leasing, admin, "/api/leasing/" + collection, rows.get(i).path("id").asText(), i, 5, Map.of());
        }
        ApiClient maintenance = Flow.maintenance();
        JsonNode vendors = Flow.rows(maintenance.get(admin, "/api/maintenance/vendors"));
        String vendor = vendors.get(0).path("id").asText();
        JsonNode orders = Flow.rows(maintenance.get(admin, "/api/maintenance/work-orders"));
        for (int i = 0; i < orders.size(); i++)
            Flow.walk(soft, maintenance, admin, "/api/maintenance/work-orders", orders.get(i).path("id").asText(), i, 7, Map.of("vendorId", vendor));
        assertTrue(soft.calls() > 100, "Expected a broad walk but made " + soft.calls() + " calls");
        soft.assertClean();
    }
}
