package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/** The AI tools with the model mocked: the rules must work alone, and a model answer must never get past the checks if it invents facts. */
class StudioToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Upstream up = Mockito.mock(Upstream.class);
    private final ModelRunner runner = Mockito.mock(ModelRunner.class);
    private final Guardrails guardrails = new Guardrails();

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @BeforeEach
    void modelOffline() {
        when(runner.ask(anyString(), anyString(), anyString())).thenReturn(null);
        when(runner.modelName()).thenReturn("test:model");
    }

    @Nested
    class Anomalies {
        private AnomalyScanTool tool;

        @BeforeEach
        void setUp() {
            tool = new AnomalyScanTool(up, runner, mapper);
        }

        @Test
        @DisplayName("two identical invoices for the same resident, period and amount are flagged as a duplicate")
        void duplicateInvoice() {
            when(up.tryGet(anyString(), contains("/invoices"))).thenReturn(json("""
                    [{"id":"1","tenant_id":"t1","tenant_name":"Ana","kind":"RENT","period":"2026-10","amount":"1500","invoice_number":"INV-1","status":"OPEN"},
                     {"id":"2","tenant_id":"t1","tenant_name":"Ana","kind":"RENT","period":"2026-10","amount":"1500","invoice_number":"INV-2","status":"OPEN"}]"""));

            Map<String, Object> result = tool.scan("Bearer x");

            List<?> findings = (List<?>) result.get("findings");
            assertThat(findings).hasSize(1);
            assertThat(((Map<?, ?>) findings.get(0)).get("type")).isEqualTo("DUPLICATE_INVOICE");
            assertThat(result.get("source")).isEqualTo("RULES");
        }

        @Test
        @DisplayName("a voided invoice is not counted as a duplicate")
        void voidIgnored() {
            when(up.tryGet(anyString(), contains("/invoices"))).thenReturn(json("""
                    [{"tenant_id":"t1","kind":"RENT","period":"2026-10","amount":"1500","status":"OPEN"},
                     {"tenant_id":"t1","kind":"RENT","period":"2026-10","amount":"1500","status":"VOID"}]"""));
            assertThat((List<?>) tool.scan("Bearer x").get("findings")).isEmpty();
        }

        @Test
        @DisplayName("a rent invoice that differs from an active lease's rent is flagged")
        void rentMismatch() {
            when(up.tryGet(anyString(), contains("/invoices"))).thenReturn(json("""
                    [{"tenant_id":"t1","tenant_name":"Ana","kind":"RENT","period":"2026-10","amount":"1400","lease_id":"L1","invoice_number":"INV-9","status":"OPEN"}]"""));
            when(up.tryGet(anyString(), contains("/leases"))).thenReturn(json("""
                    [{"id":"L1","rent":1500,"status":"ACTIVE"}]"""));

            List<?> findings = (List<?>) tool.scan("Bearer x").get("findings");

            assertThat(findings).hasSize(1);
            assertThat(((Map<?, ?>) findings.get(0)).get("type")).isEqualTo("RENT_MISMATCH");
        }

        @Test
        @DisplayName("the same expense twice on one owner statement is flagged")
        void duplicateExpense() {
            when(up.tryGet(anyString(), contains("/statements"))).thenReturn(json("""
                    [{"owner_name":"Greenfield","period":"2026-09","rent_collected":10000,"expenses":600,
                      "lines":[{"type":"Expense","date":"2026-09-12","description":"HVAC service","amount":300},
                               {"type":"Expense","date":"2026-09-12","description":"hvac service","amount":300}]}]"""));

            List<?> findings = (List<?>) tool.scan("Bearer x").get("findings");

            assertThat(findings).hasSize(1);
            assertThat(((Map<?, ?>) findings.get(0)).get("type")).isEqualTo("DUPLICATE_EXPENSE");
        }

        @Test
        @DisplayName("clean data reports nothing unusual")
        void clean() {
            when(up.tryGet(anyString(), anyString())).thenReturn(json("[]"));
            Map<String, Object> result = tool.scan("Bearer x");
            assertThat((List<?>) result.get("findings")).isEmpty();
            assertThat(result.get("summary").toString()).startsWith("Nothing unusual");
        }
    }

    @Nested
    class StatementNarrative {
        private StatementNarrativeTool tool;
        private static final String STATEMENT = """
                {"owner_name":"Greenfield","period":"2026-09","status":"DRAFT","rent_collected":11400,"fees_collected":192.5,"mgmt_fee":798,"expenses":634,"net_payout":10160.5,"lines":[]}""";

        @BeforeEach
        void setUp() {
            tool = new StatementNarrativeTool(up, runner);
            when(up.get(anyString(), anyString())).thenReturn(json(STATEMENT));
        }

        @Test
        @DisplayName("a model draft that only uses amounts from the statement is accepted")
        void accepted() {
            when(runner.ask(anyString(), anyString(), anyString())).thenReturn(json(
                    "{\"headline\":\"Net payout $10,160.50\",\"narrative\":\"We collected $11,400.00 in rent and paid you $10,160.50.\",\"watch\":[]}"));

            Map<String, Object> out = tool.draft("Bearer x", "s1");

            assertThat(out.get("source")).isEqualTo("AI");
            assertThat(out.get("narrative").toString()).contains("$11,400.00");
        }

        @Test
        @DisplayName("a model draft with an amount that is not on the statement is replaced by the template")
        void inventedAmountRejected() {
            when(runner.ask(anyString(), anyString(), anyString())).thenReturn(json(
                    "{\"headline\":\"Good month\",\"narrative\":\"Income was $11,592.50 in total.\",\"watch\":[]}"));

            Map<String, Object> out = tool.draft("Bearer x", "s1");

            assertThat(out.get("source")).isEqualTo("RULES");
            assertThat(out.get("narrative").toString()).doesNotContain("11,592.50").contains("$10,160.50");
            assertThat(out.get("checks").toString()).contains("isn't on the statement");
        }

        @Test
        @DisplayName("with no model the template is used")
        void noModel() {
            assertThat(tool.draft("Bearer x", "s1").get("source")).isEqualTo("RULES");
        }
    }

    @Nested
    class FairHousing {
        private FairHousingTool tool;

        @BeforeEach
        void setUp() {
            tool = new FairHousingTool(runner, guardrails);
        }

        @Test
        @DisplayName("listed discriminatory phrases are flagged even when no model is available")
        void phraseList() {
            Map<String, Object> out = tool.check("Great flat. No kids please.");
            assertThat(out.get("verdict")).isEqualTo("REVIEW");
            assertThat(out.get("source")).isEqualTo("RULES");
            assertThat((List<?>) out.get("issues")).isNotEmpty();
        }

        @Test
        @DisplayName("a clean description passes")
        void clean() {
            assertThat(tool.check("Sunny 2BR near transit with laundry.").get("verdict")).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("a model rewrite that still contains a listed phrase is discarded")
        void unsafeRewriteDropped() {
            when(runner.ask(anyString(), anyString(), anyString())).thenReturn(json(
                    "{\"issues\":[],\"rewrite\":\"Quiet building, adults only.\",\"verdict\":\"REVIEW\"}"));
            Map<String, Object> out = tool.check("No kids please.");
            assertThat(out.get("rewrite")).isNull();
        }

        @Test
        @DisplayName("text must be present and not too long")
        void validatesInput() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.check(" ")).hasMessageContaining("Paste the text");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.check("x".repeat(4001))).hasMessageContaining("4,000");
        }
    }

    @Nested
    class LeaseAbstract {
        @Test
        @DisplayName("amounts and dates the model gives that are not in the lease are removed")
        void unverifiedFiguresDropped() {
            LeaseAbstractTool tool = new LeaseAbstractTool(up, runner, mapper);
            when(up.get(anyString(), anyString())).thenReturn(json("""
                    {"tenant_name":"Chris","unit_label":"A","property_name":"Barton","rent":2700,"deposit":2700,"terms":{},
                     "document_text":"Rent is $2,700 per month. Lease starts November 1, 2025."}"""));
            when(runner.ask(anyString(), anyString(), anyString())).thenReturn(json("""
                    {"summary":"A lease.","keyDates":[{"label":"Start","date":"2025-11-01"},{"label":"Made up","date":"2031-01-01"}],
                     "money":[{"label":"Rent","amount":2700},{"label":"Parking","amount":999}],"obligations":[],"risks":[],"missing":[]}"""));

            Map<String, Object> out = tool.summarise("Bearer x", "L1");

            assertThat(out.get("source")).isEqualTo("AI");
            assertThat(out.get("keyDates").toString()).contains("2025-11-01").doesNotContain("2031-01-01");
            assertThat(out.get("money").toString()).contains("Rent").doesNotContain("Parking");
            assertThat(out.get("checks").toString()).contains("Removed 2");
        }
    }

    @Nested
    class ResidentOutlook {
        @Test
        @DisplayName("a resident with overdue invoices and an ending lease ranks above a quiet one")
        void ranking() {
            ResidentOutlookTool tool = new ResidentOutlookTool(up, runner, mapper, guardrails);
            when(up.tryGet(anyString(), contains("/leases"))).thenReturn(json("""
                    [{"id":"L1","tenant_id":"t1","tenant_name":"Late Larry","unit_id":"u1","rent":1000,"days_left":20},
                     {"id":"L2","tenant_id":"t2","tenant_name":"Quiet Quinn","unit_id":"u2","rent":1000,"days_left":300}]"""));
            when(up.tryGet(anyString(), contains("/invoices"))).thenReturn(json("""
                    [{"tenant_id":"t1","status":"OPEN","days_overdue":35,"balance":1000}]"""));
            when(up.tryGet(anyString(), contains("/units"))).thenReturn(json("[]"));
            when(up.tryGet(anyString(), contains("/work-orders"))).thenReturn(json("[]"));

            List<?> residents = (List<?>) tool.outlook("Bearer x").get("residents");

            assertThat(residents).hasSize(1); // the quiet resident has no reason to appear
            Map<?, ?> top = (Map<?, ?>) residents.get(0);
            assertThat(top.get("tenant")).isEqualTo("Late Larry");
            assertThat(top.get("level")).isEqualTo("HIGH");
            assertThat(top.get("reasons").toString()).contains("overdue").contains("lease ends in 20 days");
        }
    }
}
