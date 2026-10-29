package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * A stand-in for an OpenAI-compatible model, so the assistant, the studio tools and the image checks can be tested
 * through their real HTTP client without a key or a network. It answers by keywords in the conversation, so each test
 * decides which branch of the AI code runs: a tool call, several calls at once, a call to a tool that does not exist,
 * a model that never stops calling tools, an empty answer or a server error.
 */
final class FakeOpenAi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;

    FakeOpenAi(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/chat/completions", ex -> {
            byte[] out;
            int status = 200;
            try {
                JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
                String reply = answer(req);
                if (reply == null) {
                    status = 500;
                    reply = "{\"error\":\"fake model failure\"}";
                }
                out = reply.getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                status = 500;
                out = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    private static String completion(String content, ArrayNode toolCalls) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode message = root.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant").put("content", content);
        if (toolCalls != null) message.set("tool_calls", toolCalls);
        root.putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 40);
        return root.toString();
    }

    private static ArrayNode calls(String... namesAndArgs) {
        ArrayNode a = JSON.createArrayNode();
        for (int i = 0; i < namesAndArgs.length; i += 2) {
            ObjectNode c = a.addObject();
            c.put("id", "call_" + i).put("type", "function");
            c.putObject("function").put("name", namesAndArgs[i]).put("arguments", namesAndArgs[i + 1]);
        }
        return a;
    }

    /** Text of the last user message, lower-cased, or "" when there is none. */
    private static String lastUserText(JsonNode messages) {
        String text = "";
        for (JsonNode m : messages) if ("user".equals(m.path("role").asText()) && m.path("content").isTextual()) text = m.path("content").asText().toLowerCase();
        return text;
    }

    private static boolean hasImage(JsonNode messages) {
        for (JsonNode m : messages) if (m.path("content").isArray()) for (JsonNode part : m.path("content")) if ("image_url".equals(part.path("type").asText())) return true;
        return false;
    }

    static String answer(JsonNode req) {
        JsonNode messages = req.path("messages");
        String system = messages.size() > 0 ? messages.get(0).path("content").asText("") : "";
        String user = lastUserText(messages);
        if (user.contains("[model-error]")) return null;
        if (hasImage(messages)) return vision(system, messages);
        if (req.has("tools")) return assistant(messages, user);
        return studio(system, user, messages.toString().length());
    }

    private static String vision(String system, JsonNode messages) {
        String payload = messages.toString();
        int variant = Math.abs(payload.hashCode()) % 4;
        if (system.contains("maintenance coordinator")) {
            if (variant == 3) return completion("not json at all", null);
            return completion("{\"category\":\"" + (variant == 0 ? "PLUMBING" : variant == 1 ? "ELECTRICAL" : "weird") + "\",\"priority\":\"" + (variant == 1 ? "EMERGENCY" : "NORMAL")
                    + "\",\"safetyRisk\":" + (variant == 1) + ",\"summary\":\"A dripping pipe under the sink\",\"likelyCause\":\"Worn fitting\",\"suggestedAction\":\"Replace the fitting\","
                    + "\"estimateLow\":90,\"estimateHigh\":240,\"tenantAdvice\":\"Place a bucket under the leak\",\"confidence\":0.82}", null);
        }
        return switch (variant) {
            case 0 -> completion("{\"verdict\":\"SAFE\",\"confidence\":0.97,\"category\":\"PROPERTY\",\"labels\":[\"kitchen\",\"counter\"],\"reason\":\"A clean kitchen.\",\"quality\":88,\"caption\":\"Bright kitchen with white counters\",\"qualityIssues\":[]}", null);
            case 1 -> completion("{\"verdict\":\"UNSAFE\",\"confidence\":95,\"category\":\"EXPLICIT\",\"labels\":[\"adult\"],\"reason\":\"Adult content.\",\"quality\":40,\"qualityIssues\":[\"dark\",\"blurry\"]}", null);
            case 2 -> completion("{\"verdict\":\"MAYBE\",\"confidence\":0.4,\"category\":\"SOMETHING\",\"labels\":[],\"reason\":\"\"}", null);
            default -> completion("this is not json", null);
        };
    }

    private static String assistant(JsonNode messages, String user) {
        JsonNode last = messages.get(messages.size() - 1);
        boolean afterTool = "tool".equals(last.path("role").asText());
        if (user.contains("loop")) return completion("", calls("portfolio_overview", "{}"));
        if (afterTool) {
            if (user.contains("empty")) return completion("", null);
            if (user.contains("fairhousing")) return completion("This is a great place for young professionals without kids. Reach me at 555-123-4567 or me@example.com.", null);
            return completion("Here is what I found for you. The numbers above come straight from your data.", null);
        }
        if (user.contains("report a problem") || user.contains("create a work order")) return completion("", calls("create_work_order", "{\"title\":\"Leaking tap\",\"description\":\"Kitchen tap drips\"}"));
        if (user.contains("send reminders")) return completion("", calls("send_payment_reminders", "{}"));
        if (user.contains("unknown tool")) return completion("", calls("delete_everything", "{}"));
        if (user.contains("both")) return completion("", calls("list_vacant_units", "{}", "list_overdue_tenants", "{}"));
        if (user.contains("bad arguments")) return completion("", calls("list_applications", "{\"status\":\"" + "SUBMITTED" + "\"}"));
        if (user.contains("overdue") || user.contains("late")) return completion("", calls("list_overdue_tenants", "{}"));
        if (user.contains("vacant")) return completion("", calls("list_vacant_units", "{\"broker\":\"Emily\"}"));
        if (user.contains("broker")) return completion("", calls("broker_workload", "{}"));
        if (user.contains("application")) return completion("", calls("list_applications", "{\"status\":\"SCREENED\"}"));
        if (user.contains("document")) return completion("", calls("search_documents", "{\"q\":\"lease\",\"category\":\"LEASE\",\"expiring\":\"true\"}", "list_missing_documents", "{}"));
        if (user.contains("signature")) return completion("", calls("list_signature_requests", "{\"status\":\"PENDING\"}"));
        if (user.contains("work order") || user.contains("maintenance")) return completion("", calls("list_work_orders", "{\"priority\":\"EMERGENCY\",\"broker\":\"Robert\"}"));
        if (user.contains("sla")) return completion("", calls("list_sla_breaches", "{}"));
        if (user.contains("expiring")) return completion("", calls("list_expiring_leases", "{\"withinDays\":400}"));
        if (user.contains("vendor")) return completion("", calls("list_vendors", "{}"));
        if (user.contains("payment")) return completion("", calls("list_recent_payments", "{}"));
        if (user.contains("opportunit") || user.contains("profit")) return completion("", calls("find_opportunities", "{}"));
        if (user.contains("my balance") || user.contains("account")) return completion("", calls("my_account", "{}"));
        if (user.contains("propert")) return completion("", calls("list_properties", "{}"));
        if (user.contains("overview")) return completion("", calls("portfolio_overview", "{}"));
        return completion("I can help with your portfolio. Ask about vacancies, rent, repairs or leases.", null);
    }

    private static String studio(String system, String user, int size) {
        String s = system.toLowerCase();
        boolean variant = size % 2 == 0;
        if (s.contains("write rental listing copy")) return completion("{\"title\":\"Sunny two-bedroom near the park\",\"description\":\"" + "A bright corner home with new floors, a private balcony and a quiet street. ".repeat(3) + "\"}", null);
        if (s.contains("reply") && s.contains("rental enquiry")) return completion("{\"reply\":\"Thanks for reaching out! The home is available and we would love to show it. Would Thursday work?\",\"score\":82,\"intent\":\"TOUR\"}", null);
        if (s.contains("summarise an applicant screening")) return completion("{\"summary\":\"Income is 3.1 times the rent and the background check is clear. Recommend approval.\"}", null);
        if (s.contains("triage a maintenance request")) return completion("{\"category\":\"PLUMBING\",\"priority\":\"HIGH\",\"estimate\":260,\"summary\":\"Likely a worn supply line under the sink.\"}", null);
        if (s.contains("catch up on a conversation")) return completion("{\"summary\":\"The resident asked about a repair visit and staff proposed Friday.\",\"openQuestions\":[\"Is Friday afternoon okay?\"],\"suggestedReply\":\"Friday afternoon works. We will arrive between 1 and 3.\"}", null);
        if (s.contains("extract facts from a us residential lease"))
            return completion("{\"summary\":\"A twelve month lease with monthly rent.\",\"keyDates\":[{\"label\":\"Start\",\"date\":\"2020-01-01\"},{\"label\":\"Made up\",\"date\":\"1999-09-09\"}],"
                    + "\"money\":[{\"label\":\"Invented fee\",\"amount\":98765},{\"label\":\"Small\",\"amount\":1}],\"obligations\":[{\"party\":\"Tenant\",\"text\":\"Pay rent monthly.\"}],"
                    + "\"risks\":[{\"severity\":\"LOW\",\"text\":\"No renewal clause.\"}],\"missing\":[\"Pet policy\"]}", null);
        if (s.contains("short explanation that goes at the top"))
            return variant ? completion("{\"headline\":\"A steady month\",\"narrative\":\"Rent came in on time and expenses were modest this month.\",\"watch\":[\"Roof quote\"]}", null)
                    : completion("{\"headline\":\"A strong month\",\"narrative\":\"We collected $987,654.32 in rent this month.\",\"watch\":[]}", null);
        if (s.contains("plan outreach")) return completion("{\"plans\":{\"someone\":\"Call this week to check in.\"}}", null);
        if (s.contains("flagged billing items")) return completion("{\"summary\":\"Check the duplicate invoice first because it affects the tenant balance.\",\"priority\":[\"x\"]}", null);
        if (s.contains("fair housing act")) return completion("{\"issues\":[{\"phrase\":\"perfect for young professionals\",\"why\":\"Suggests an age or family preference.\",\"protectedClass\":\"Familial status\"}],\"rewrite\":\"Close to transit and restaurants.\",\"verdict\":\"REVIEW\"}", null);
        return completion("{\"summary\":\"ok\"}", null);
    }
}
