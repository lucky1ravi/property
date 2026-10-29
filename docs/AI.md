# AI

## Providers

`AI_PROVIDER` selects `openai`, `bedrock` or nothing. With nothing configured, every feature falls back to deterministic rules and the response says so. `OpenAiLlm` and `BedrockLlm` implement the `Llm` interface and `LlmProvider` chooses one from the environment, so callers do not change.

## Assistant

The assistant answers questions by calling permission-checked tools (for example "who is overdue on rent"). It never reads data directly. Any action that changes data is proposed first and runs only after the user confirms.

## AI studio tools

| Tool | Purpose |
| --- | --- |
| `LeaseAbstractTool` | Key terms from lease text |
| `AnomalyScanTool` | Unusual charges and payments |
| `StatementNarrativeTool` | Plain-language owner statement summary |
| `ResidentOutlookTool` | Renewal and risk outlook |
| `PhotoTriageTool` | Vision check of maintenance photos |
| `FairHousingTool` | Screens listing and message text for fair-housing risk |

Each tool is one class. They share `ModelRunner`, which checks that a model is enabled, calls it, and writes the audit row.

## Guardrails (`Guardrails`)

* Prompt-injection screening on user input.
* PII redaction before text is sent to a model or stored in the audit.
* Fair-housing screening on generated and user-supplied text.
* Per-user rate limits.
* Confirm-before-write for any change.

## Audit

Every call writes `ai.ai_audit`: capability, redacted prompt, tools used, provider, token counts, whether it was blocked and why, whether the offline fallback ran, and latency. Fields are length-limited before insert.
