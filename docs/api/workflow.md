# Workflow service API

Automation rules, workflow definitions, notifications and preferences, conversations, and the operator delivery log.

| | |
| --- | --- |
| Base path | `/api/workflow` through the gateway (port 8080) |
| Live contract | `/api/workflow/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **38** (0 open to visitors, 3 for platform operators) |
| Source | `backend/workflow-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [automation](#automation) (2)
- [chat](#chat) (7)
- [deliveries](#deliveries) (1)
- [notifications](#notifications) (5)
- [platform](#platform) (3)
- [rules](#rules) (6)
- [webhooks](#webhooks) (10)
- [workflows](#workflows) (4)

## automation

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/automation/events` | `AUTOMATION:VIEW` | Lists recent events and the rules they triggered. | `WorkflowController.events` |
| GET | `/api/workflow/automation/summary` | `AUTOMATION:VIEW` | Returns automation figures: active rules, runs and failures in the last 30 days, estimated time saved and the latest runs. | `WorkflowController.summary` |

## chat

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/chat/contacts` | `CONVERSATION:CREATE` | Lists people the signed-in user may start a conversation with. | `WorkflowController.contacts` |
| GET | `/api/workflow/chat/conversations` | `CONVERSATION:VIEW` | Lists the signed-in user's conversations. | `WorkflowController.conversations` |
| POST | `/api/workflow/chat/conversations` | `CONVERSATION:CREATE` | Starts a conversation, optionally about a property, lease or work order. | `WorkflowController.startConversation` |
| GET | `/api/workflow/chat/conversations/{id}` | `CONVERSATION:VIEW` | Returns a conversation with its messages. | `WorkflowController.conversation` |
| POST | `/api/workflow/chat/conversations/{id}/assist` | `CONVERSATION:VIEW` | Summarises a conversation with AI. | `WorkflowController.assist` |
| POST | `/api/workflow/chat/conversations/{id}/messages` | `CONVERSATION:CREATE` | Posts a message to a conversation. | `WorkflowController.postMessage` |
| POST | `/api/workflow/chat/conversations/{id}/resolve` | `CONVERSATION:CREATE` | Marks a conversation resolved, or reopens it. | `WorkflowController.resolveConversation` |

## deliveries

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/deliveries` | `AUTOMATION:VIEW` | Lists recent notification deliveries (email and in-app) for troubleshooting. | `WorkflowController.deliveries` |

## notifications

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/notifications` | signed-in | Lists the signed-in user's notifications. | `WorkflowController.notifications` |
| POST | `/api/workflow/notifications/{id}/read` | signed-in | Marks one notification as read. | `WorkflowController.read` |
| GET | `/api/workflow/notifications/preferences` | signed-in | Returns the signed-in user's notification preferences. | `WorkflowController.preferences` |
| PUT | `/api/workflow/notifications/preferences` | signed-in | Saves the signed-in user's notification preferences. | `WorkflowController.savePreferences` |
| POST | `/api/workflow/notifications/read-all` | signed-in | Marks all of the user's notifications as read. | `WorkflowController.readAll` |

## platform

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/platform/deliveries` | `PLATFORM:VIEW` | Deliveries across all companies, paged: page, size, sort, q and f.. | `PlatformDeliveryController.list` |
| POST | `/api/workflow/platform/deliveries/{id}/reveal` | `PLATFORM:CONFIGURE` | One delivery in full (whole recipient and subject, never the message text), for support work. | `PlatformDeliveryController.reveal` |
| GET | `/api/workflow/platform/deliveries/summary` | `PLATFORM:VIEW` | Totals for the last 7 days. | `PlatformDeliveryController.summary` |

## rules

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/rules` | `AUTOMATION:VIEW` | Lists automation rules. | `WorkflowController.rules` |
| POST | `/api/workflow/rules` | `AUTOMATION:CONFIGURE` | Creates an automation rule (when an event happens, do an action). | `WorkflowController.createRule` |
| GET | `/api/workflow/rules/{id}` | `AUTOMATION:VIEW` | Returns one automation rule. | `WorkflowController.rule` |
| PUT | `/api/workflow/rules/{id}` | `AUTOMATION:CONFIGURE` | Changes an automation rule. | `WorkflowController.updateRule` |
| DELETE | `/api/workflow/rules/{id}` | `AUTOMATION:CONFIGURE` | Deletes a rule. | `WorkflowController.deleteRule` |
| POST | `/api/workflow/rules/{id}/toggle` | `AUTOMATION:CONFIGURE` | Turns a rule on or off. | `WorkflowController.toggle` |

## webhooks

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/webhooks` | `WEBHOOK:VIEW` | The company's endpoints with how their recent deliveries went (never the secret). | `WebhookController.list` |
| POST | `/api/workflow/webhooks` | `WEBHOOK:CREATE` | Adds an endpoint. | `WebhookController.create` |
| PUT | `/api/workflow/webhooks/{id}` | `WEBHOOK:EDIT` | Changes an endpoint's name, url, events or active flag (false switches it off). | `WebhookController.update` |
| DELETE | `/api/workflow/webhooks/{id}` | `WEBHOOK:DELETE` | Deletes an endpoint and its delivery log. | `WebhookController.delete` |
| POST | `/api/workflow/webhooks/{id}/rotate-secret` | `WEBHOOK:EDIT` | Makes a new signing secret and returns it once. | `WebhookController.rotate` |
| POST | `/api/workflow/webhooks/{id}/test` | `WEBHOOK:EDIT` | Sends a sample event to the endpoint now and reports what it answered. | `WebhookController.test` |
| GET | `/api/workflow/webhooks/deliveries` | `WEBHOOK:VIEW` | The delivery log, newest first (paged). | `WebhookController.deliveries` |
| GET | `/api/workflow/webhooks/deliveries/{id}` | `WEBHOOK:VIEW` | One delivery, with the exact JSON that was sent. | `WebhookController.delivery` |
| POST | `/api/workflow/webhooks/deliveries/{id}/retry` | `WEBHOOK:EDIT` | Queues a failed delivery to be tried again now. | `WebhookController.retry` |
| GET | `/api/workflow/webhooks/events` | `WEBHOOK:VIEW` | The events an endpoint can subscribe to. | `WebhookController.events` |

## workflows

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/workflow/workflows` | `WORKFLOW:VIEW` | Lists the workflows with their state and transition counts and whether the company has customised each. | `WorkflowController.workflows` |
| GET | `/api/workflow/workflows/{key}` | `WORKFLOW:VIEW` | Returns a workflow's current definition next to the built-in default. | `WorkflowController.workflow` |
| PUT | `/api/workflow/workflows/{key}` | `WORKFLOW:CONFIGURE` | Saves a customised workflow after validating its states and transitions. | `WorkflowController.saveWorkflow` |
| POST | `/api/workflow/workflows/{key}/reset` | `WORKFLOW:CONFIGURE` | Discards the company's customisation and returns to the built-in workflow. | `WorkflowController.reset` |
