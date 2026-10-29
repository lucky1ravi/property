# Workflow service API

Base path `/api/workflow` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/workflow/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/workflow-service`: **25 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| GET | `/api/workflow/automation/events` | AUTOMATION:VIEW | `WorkflowController.events` |
| GET | `/api/workflow/automation/summary` | AUTOMATION:VIEW | `WorkflowController.summary` |
| GET | `/api/workflow/chat/contacts` | CONVERSATION:CREATE | `WorkflowController.contacts` |
| GET | `/api/workflow/chat/conversations` | CONVERSATION:VIEW | `WorkflowController.conversations` |
| POST | `/api/workflow/chat/conversations` | CONVERSATION:CREATE | `WorkflowController.startConversation` |
| GET | `/api/workflow/chat/conversations/{id}` | CONVERSATION:VIEW | `WorkflowController.conversation` |
| POST | `/api/workflow/chat/conversations/{id}/assist` | CONVERSATION:VIEW | `WorkflowController.assist` |
| POST | `/api/workflow/chat/conversations/{id}/messages` | CONVERSATION:CREATE | `WorkflowController.postMessage` |
| POST | `/api/workflow/chat/conversations/{id}/resolve` | CONVERSATION:CREATE | `WorkflowController.resolveConversation` |
| GET | `/api/workflow/deliveries` | AUTOMATION:VIEW | `WorkflowController.deliveries` |
| GET | `/api/workflow/notifications` | signed-in | `WorkflowController.notifications` |
| GET | `/api/workflow/notifications/preferences` | signed-in | `WorkflowController.preferences` |
| PUT | `/api/workflow/notifications/preferences` | signed-in | `WorkflowController.savePreferences` |
| POST | `/api/workflow/notifications/read-all` | signed-in | `WorkflowController.readAll` |
| POST | `/api/workflow/notifications/{id}/read` | signed-in | `WorkflowController.read` |
| GET | `/api/workflow/rules` | AUTOMATION:VIEW | `WorkflowController.rules` |
| POST | `/api/workflow/rules` | AUTOMATION:CONFIGURE | `WorkflowController.createRule` |
| DELETE | `/api/workflow/rules/{id}` | AUTOMATION:CONFIGURE | `WorkflowController.deleteRule` |
| GET | `/api/workflow/rules/{id}` | AUTOMATION:VIEW | `WorkflowController.rule` |
| PUT | `/api/workflow/rules/{id}` | AUTOMATION:CONFIGURE | `WorkflowController.updateRule` |
| POST | `/api/workflow/rules/{id}/toggle` | AUTOMATION:CONFIGURE | `WorkflowController.toggle` |
| GET | `/api/workflow/workflows` | WORKFLOW:VIEW | `WorkflowController.workflows` |
| GET | `/api/workflow/workflows/{key}` | WORKFLOW:VIEW | `WorkflowController.workflow` |
| PUT | `/api/workflow/workflows/{key}` | WORKFLOW:CONFIGURE | `WorkflowController.saveWorkflow` |
| POST | `/api/workflow/workflows/{key}/reset` | WORKFLOW:CONFIGURE | `WorkflowController.reset` |
