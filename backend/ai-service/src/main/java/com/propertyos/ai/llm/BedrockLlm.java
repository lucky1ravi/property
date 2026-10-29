package com.propertyos.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

/** AWS Bedrock via the Converse API with tool use. Credentials come from the standard AWS provider chain. */
public class BedrockLlm implements Llm {
    private final BedrockRuntimeClient client;
    private final ObjectMapper mapper;
    private final String modelId;
    private final boolean vision;

    public BedrockLlm(ObjectMapper mapper, String region, String modelId) {
        this(mapper, region, modelId, false);
    }

    public BedrockLlm(ObjectMapper mapper, String region, String modelId, boolean vision) {
        this.mapper = mapper;
        this.modelId = modelId;
        this.vision = vision;
        this.client = BedrockRuntimeClient.builder().region(Region.of(region)).build();
    }

    @Override
    public String provider() {
        return "bedrock";
    }

    @Override
    public String model() {
        return modelId;
    }

    @Override
    public boolean supportsVision() {
        return vision;
    }

    @Override
    public String vision(String system, String prompt, byte[] image, String mime) throws Exception {
        ImageFormat fmt = mime.contains("png") ? ImageFormat.PNG : mime.contains("gif") ? ImageFormat.GIF : mime.contains("webp") ? ImageFormat.WEBP : ImageFormat.JPEG;
        Message m = Message.builder().role(ConversationRole.USER).content(
                ContentBlock.fromText(prompt),
                ContentBlock.fromImage(ImageBlock.builder().format(fmt).source(ImageSource.builder().bytes(software.amazon.awssdk.core.SdkBytes.fromByteArray(image)).build()).build())).build();
        ConverseResponse res = client.converse(ConverseRequest.builder().modelId(modelId).system(SystemContentBlock.builder().text(system).build())
                .messages(m).inferenceConfig(InferenceConfiguration.builder().maxTokens(600).temperature(0f).build()).build());
        StringBuilder text = new StringBuilder();
        for (ContentBlock b : res.output().message().content()) if (b.text() != null) text.append(b.text());
        return text.toString();
    }

    @Override
    public Reply chat(String system, List<Msg> messages, List<ToolSpec> tools, boolean jsonOutput) throws Exception {
        List<Message> msgs = new ArrayList<>();
        List<ContentBlock> pendingResults = new ArrayList<>();
        for (Msg m : messages) {
            if ("tool".equals(m.role())) {
                pendingResults.add(ContentBlock.builder().toolResult(ToolResultBlock.builder().toolUseId(m.toolCallId())
                        .content(ToolResultContentBlock.builder().text(m.content()).build()).build()).build());
                continue;
            }
            if (!pendingResults.isEmpty()) {
                msgs.add(Message.builder().role(ConversationRole.USER).content(pendingResults).build());
                pendingResults = new ArrayList<>();
            }
            if ("assistant".equals(m.role())) {
                List<ContentBlock> blocks = new ArrayList<>();
                if (m.content() != null && !m.content().isBlank()) blocks.add(ContentBlock.fromText(m.content()));
                for (ToolCall c : m.toolCalls())
                    blocks.add(ContentBlock.builder().toolUse(ToolUseBlock.builder().toolUseId(c.id()).name(c.name()).input(toDoc(c.arguments())).build()).build());
                msgs.add(Message.builder().role(ConversationRole.ASSISTANT).content(blocks).build());
            } else {
                msgs.add(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(m.content())).build());
            }
        }
        if (!pendingResults.isEmpty()) msgs.add(Message.builder().role(ConversationRole.USER).content(pendingResults).build());
        ConverseRequest.Builder req = ConverseRequest.builder().modelId(modelId)
                .system(SystemContentBlock.builder().text(jsonOutput ? system + "\nRespond with a single valid JSON object and nothing else." : system).build())
                .messages(msgs).inferenceConfig(InferenceConfiguration.builder().maxTokens(1500).temperature(0.2f).build());
        if (tools != null && !tools.isEmpty()) {
            List<Tool> ts = new ArrayList<>();
            for (ToolSpec t : tools)
                ts.add(Tool.builder().toolSpec(ToolSpecification.builder().name(t.name()).description(t.description())
                        .inputSchema(ToolInputSchema.fromJson(toDoc(t.schema()))).build()).build());
            req.toolConfig(ToolConfiguration.builder().tools(ts).build());
        }
        ConverseResponse res = client.converse(req.build());
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock b : res.output().message().content()) {
            if (b.text() != null) text.append(b.text());
            if (b.toolUse() != null) calls.add(new ToolCall(b.toolUse().toolUseId(), b.toolUse().name(), clean(mapper.readTree(docToJson(b.toolUse().input())))));
        }
        return new Reply(text.toString(), calls, res.usage().inputTokens(), res.usage().outputTokens());
    }

    /** Some models send {"": ""} for a tool with no parameters, and the API rejects that when it is sent back. */
    private JsonNode clean(JsonNode n) {
        if (n == null || !n.isObject()) return mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
        n.fields().forEachRemaining(e -> { if (!e.getKey().isBlank()) out.set(e.getKey(), e.getValue()); });
        return out;
    }

    private Document toDoc(JsonNode n) {
        if (n == null || n.isNull()) return Document.fromNull();
        if (n.isObject()) {
            Map<String, Document> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> { if (!e.getKey().isBlank()) m.put(e.getKey(), toDoc(e.getValue())); });
            return Document.fromMap(m);
        }
        if (n.isArray()) {
            List<Document> l = new ArrayList<>();
            n.forEach(x -> l.add(toDoc(x)));
            return Document.fromList(l);
        }
        if (n.isBoolean()) return Document.fromBoolean(n.asBoolean());
        if (n.isNumber()) return Document.fromNumber(n.asDouble());
        return Document.fromString(n.asText());
    }

    private String docToJson(Document d) throws Exception {
        return mapper.writeValueAsString(unwrap(d));
    }

    private Object unwrap(Document d) {
        if (d == null || d.isNull()) return null;
        if (d.isMap()) {
            Map<String, Object> m = new LinkedHashMap<>();
            d.asMap().forEach((k, v) -> m.put(k, unwrap(v)));
            return m;
        }
        if (d.isList()) return d.asList().stream().map(this::unwrap).toList();
        if (d.isBoolean()) return d.asBoolean();
        if (d.isNumber()) return d.asNumber().doubleValue();
        return d.asString();
    }
}
