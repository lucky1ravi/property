package com.propertyos.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Provider-neutral chat interface with tool calling. Implementations: OpenAI, AWS Bedrock. */
public interface Llm {
    record ToolSpec(String name, String description, JsonNode schema) {
    }

    record ToolCall(String id, String name, JsonNode arguments) {
    }

    /** role: user, assistant (may carry toolCalls) or tool (result for toolCallId). */
    record Msg(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public static Msg user(String text) {
            return new Msg("user", text, List.of(), null);
        }

        public static Msg assistant(String text, List<ToolCall> calls) {
            return new Msg("assistant", text, calls == null ? List.of() : calls, null);
        }

        public static Msg tool(String id, String result) {
            return new Msg("tool", result, List.of(), id);
        }
    }

    record Reply(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
    }

    String provider();

    String model();

    Reply chat(String system, List<Msg> messages, List<ToolSpec> tools, boolean jsonOutput) throws Exception;
}
