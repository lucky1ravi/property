package com.propertyos.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Bedrock adapter with the AWS client mocked: request building, tool calls and answer parsing. */
class BedrockLlmTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);

    private static ConverseResponse answer(ContentBlock... blocks) {
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(Message.builder().role(ConversationRole.ASSISTANT).content(blocks).build()).build())
                .usage(TokenUsage.builder().inputTokens(11).outputTokens(7).build()).build();
    }

    @Test
    void reportsItsIdentity() {
        BedrockLlm llm = new BedrockLlm(mapper, client, "model-x", true);
        assertThat(llm.provider()).isEqualTo("bedrock");
        assertThat(llm.model()).isEqualTo("model-x");
        assertThat(llm.supportsVision()).isTrue();
        assertThat(new BedrockLlm(mapper, "us-east-1", "m").supportsVision()).isFalse();
    }

    @Test
    void sendsAConversationWithToolsAndReadsTheToolCalls() throws Exception {
        Document args = Document.fromMap(Map.of("status", Document.fromString("OPEN"), "", Document.fromString("")));
        when(client.converse(any(ConverseRequest.class))).thenReturn(answer(
                ContentBlock.fromText("Looking that up."),
                ContentBlock.builder().toolUse(ToolUseBlock.builder().toolUseId("t1").name("list_things").input(args).build()).build()));
        BedrockLlm llm = new BedrockLlm(mapper, client, "model-x", false);
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"},\"ok\":{\"type\":\"boolean\"},\"tags\":[\"a\"],\"none\":null,\"x\":1.5}}");
        var call = new Llm.ToolCall("c1", "list_things", mapper.readTree("{\"status\":\"OPEN\"}"));
        Llm.Reply reply = llm.chat("system", List.of(
                Llm.Msg.user("hello"),
                Llm.Msg.assistant("checking", List.of(call)),
                Llm.Msg.tool("c1", "[1,2]"),
                Llm.Msg.tool("c2", "[]"),
                Llm.Msg.assistant("", null),
                Llm.Msg.user("thanks"),
                Llm.Msg.tool("c3", "late")),
                List.of(new Llm.ToolSpec("list_things", "Lists things", schema)), true);
        assertThat(reply.text()).isEqualTo("Looking that up.");
        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().get(0).arguments().has("")).isFalse();
        assertThat(reply.toolCalls().get(0).arguments().path("status").asText()).isEqualTo("OPEN");
        assertThat(reply.inputTokens()).isEqualTo(11);
        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(sent.capture());
        assertThat(sent.getValue().system().get(0).text()).contains("single valid JSON");
        assertThat(sent.getValue().toolConfig().tools()).hasSize(1);
    }

    @Test
    void worksWithoutToolsAndTreatsOddToolArgumentsAsEmpty() throws Exception {
        when(client.converse(any(ConverseRequest.class))).thenReturn(answer(
                ContentBlock.builder().toolUse(ToolUseBlock.builder().toolUseId("t").name("n").input(Document.fromString("nope")).build()).build(),
                ContentBlock.builder().toolUse(ToolUseBlock.builder().toolUseId("u").name("m").input(Document.fromList(List.of(
                        Document.fromBoolean(true), Document.fromNumber(2), Document.fromNull()))).build()).build()));
        BedrockLlm llm = new BedrockLlm(mapper, client, "m", false);
        Llm.Reply reply = llm.chat("sys", List.of(Llm.Msg.user("hi")), List.of(), false);
        assertThat(reply.toolCalls()).hasSize(2);
        assertThat(reply.toolCalls().get(0).arguments().isEmpty()).isTrue();
        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(sent.capture());
        assertThat(sent.getValue().toolConfig()).isNull();
        assertThat(llm.chat("sys", List.of(Llm.Msg.user("hi")), null, false)).isNotNull();
    }

    @Test
    void readsAnImage() throws Exception {
        when(client.converse(any(ConverseRequest.class))).thenReturn(answer(ContentBlock.fromText("{\"verdict\":\"SAFE\"}")));
        BedrockLlm llm = new BedrockLlm(mapper, client, "m", true);
        for (String mime : List.of("image/png", "image/gif", "image/webp", "image/jpeg"))
            assertThat(llm.vision("sys", "look", new byte[]{1, 2, 3}, mime)).contains("SAFE");
    }
}
