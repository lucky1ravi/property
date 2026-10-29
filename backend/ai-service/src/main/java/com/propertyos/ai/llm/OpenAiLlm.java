package com.propertyos.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** OpenAI-compatible chat completions with function calling (works with any compatible gateway via OPENAI_BASE_URL). */
public class OpenAiLlm implements Llm {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String key, model, baseUrl;
    /** Some newer models only accept the default temperature; switched off automatically after the first rejection. */
    private volatile boolean sendTemperature = true;

    public OpenAiLlm(ObjectMapper mapper, String key, String model, String baseUrl) {
        this.mapper = mapper;
        this.key = key;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    /**
     * Returns {@code openai}.
     */
    @Override
    public String provider() {
        return "openai";
    }

    /**
     * Returns the model name in use.
     */
    @Override
    public String model() {
        return model;
    }

    /**
     * Tells whether image input is allowed; set {@code OPENAI_VISION=false} to turn it off.
     */
    @Override
    public boolean supportsVision() {
        return !"false".equalsIgnoreCase(System.getenv("OPENAI_VISION"));
    }

    /**
     * Asks the model about an image.
     *
     * @param system the instructions
     * @param prompt the question
     * @param image  the image bytes
     * @param mime   the image type
     * @return the model's text answer
     * @throws Exception when the call fails
     */
    @Override
    public String vision(String system, String prompt, byte[] image, String mime) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        ArrayNode parts = msgs.addObject().put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", prompt);
        parts.addObject().put("type", "image_url").putObject("image_url").put("url", "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(image));
        body.putObject("response_format").put("type", "json_object");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) throw new IllegalStateException("Vision model returned " + res.statusCode());
        return mapper.readTree(res.body()).path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * Sends a conversation, optionally with tools, and returns the model's answer or tool calls.
     *
     * @param system     the instructions
     * @param messages   the conversation so far
     * @param tools      tools the model may call, or empty
     * @param jsonOutput true to ask for a JSON answer
     * @throws Exception when the call fails
     */
    @Override
    public Reply chat(String system, List<Msg> messages, List<ToolSpec> tools, boolean jsonOutput) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        if (sendTemperature) body.put("temperature", 0.2);
        String effort = System.getenv("OPENAI_REASONING_EFFORT");
        if (effort != null && !effort.isBlank()) body.put("reasoning_effort", effort.trim());
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        for (Msg m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            if ("tool".equals(m.role())) {
                n.put("tool_call_id", m.toolCallId());
                n.put("content", m.content());
            } else {
                n.put("content", m.content() == null ? "" : m.content());
                if (!m.toolCalls().isEmpty()) {
                    ArrayNode tc = n.putArray("tool_calls");
                    for (ToolCall c : m.toolCalls()) {
                        ObjectNode t = tc.addObject();
                        t.put("id", c.id()).put("type", "function");
                        t.putObject("function").put("name", c.name()).put("arguments", c.arguments().toString());
                    }
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode ts = body.putArray("tools");
            for (ToolSpec t : tools) {
                ObjectNode f = ts.addObject().put("type", "function").putObject("function");
                f.put("name", t.name()).put("description", t.description());
                f.set("parameters", t.schema());
            }
        }
        if (jsonOutput) body.putObject("response_format").put("type", "json_object");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 400 && sendTemperature && res.body().toLowerCase().contains("temperature")) {
            sendTemperature = false;
            body.remove("temperature");
            req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("OpenAI returned " + res.statusCode() + ": " + trim(res.body()));
        JsonNode root = mapper.readTree(res.body());
        JsonNode msg = root.path("choices").path(0).path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            String args = c.path("function").path("arguments").asText("{}");
            calls.add(new ToolCall(c.path("id").asText(), c.path("function").path("name").asText(), mapper.readTree(args.isBlank() ? "{}" : args)));
        }
        return new Reply(msg.path("content").asText(""), calls, root.path("usage").path("prompt_tokens").asInt(), root.path("usage").path("completion_tokens").asInt());
    }

    private static String trim(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
