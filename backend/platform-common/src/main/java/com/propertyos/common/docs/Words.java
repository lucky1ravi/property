package com.propertyos.common.docs;

import org.springframework.util.StringUtils;

import java.util.Arrays;

/** Small text helpers that turn code names into readable documentation labels. */
final class Words {
    private Words() { }

    /**
     * Leading words in a method name that read as the action ("list", "create", ...).
     */
    private static final java.util.Set<String> VERBS = java.util.Set.of("get", "list", "create", "update", "delete", "remove", "add", "set", "send", "submit", "approve", "reject",
            "cancel", "confirm", "mark", "run", "start", "stop", "upload", "download", "sign", "decline", "assign", "close", "open", "publish", "export", "import", "generate",
            "preview", "toggle", "reset", "check", "resend", "invite", "enable", "disable", "suspend", "reactivate", "complete", "schedule", "review", "move", "apply", "save", "login", "register", "verify", "search", "ask", "chat", "triage");

    /** A readable operation title: "approveLease" is "Approve lease"; a bare noun such as "units" takes its verb from the HTTP method ("List units" for GET). */
    static String summary(String methodName, org.springframework.http.HttpMethod verb) {
        String first = methodName.split("(?=[A-Z])")[0].toLowerCase();
        if (VERBS.contains(first)) return sentence(methodName);
        String prefix = switch (verb.name()) {
            case "GET" -> "Get";
            case "POST" -> "Create";
            case "DELETE" -> "Delete";
            default -> "Update";
        };
        return prefix + " " + sentence(methodName).toLowerCase();
    }

    /** Groups operations by resource: "/api/leasing/leases/{id}" is "Leases"; "/api/leasing/public/listings" is "Public listings". */
    static String resourceTag(String path) {
        String[] parts = Arrays.stream(path.split("/")).filter(s -> !s.isBlank() && !s.startsWith("{")).toArray(String[]::new);
        int i = 2; // skip "api" and the service name
        boolean open = i < parts.length && parts[i].equals("public");
        if (open) i++;
        String name = i < parts.length ? parts[i] : (parts.length > 1 ? parts[1] : "General");
        String label = StringUtils.capitalize(name.replace('-', ' '));
        return open ? "Public " + label.toLowerCase() : label;
    }

    /** "approveLease" becomes "Approve lease". */
    static String sentence(String camel) {
        String spaced = camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return StringUtils.capitalize(spaced);
    }

    /** "LeasingController" becomes "Leasing"; "AiTools" becomes "Ai Tools". */
    static String title(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
    }

    /** The tag springdoc derives by default, so that we know when nobody chose one explicitly. */
    static String defaultTag(Class<?> controller) {
        String n = controller.getSimpleName();
        return n.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
