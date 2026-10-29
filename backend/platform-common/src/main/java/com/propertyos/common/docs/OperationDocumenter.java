package com.propertyos.common.docs;

import com.propertyos.common.security.RequiresPermission;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

/**
 * Fills in what every operation has in common so controllers stay free of documentation noise:
 * a readable summary and tag, the permission the endpoint needs, the standard error responses,
 * and whether it is open (under {@code /public/}) or needs a token.
 * Explicit {@code @Operation} or {@code @Tag} annotations always win over these defaults.
 */
@Component
public class OperationDocumenter implements OperationCustomizer {
    /**
     * Standard error responses added to every endpoint, by status code.
     */
    private static final Map<String, String> ERRORS = Map.of(
            "400", "The request was malformed or failed validation.",
            "401", "Missing, expired or invalid token.",
            "403", "The caller lacks the required permission, or the feature is off for the company.",
            "404", "The record doesn't exist or isn't visible to the caller's company.",
            "409", "The change conflicts with existing data.");

    /**
     * Fills in whatever the developer left out for one endpoint: summary, tag, required permission, standard error responses and the security requirement.
     */
    @Override
    public Operation customize(Operation op, HandlerMethod method) {
        String path = fullPath(method);
        HttpMethod verb = httpMethod(method);
        if (op.getSummary() == null || op.getSummary().isBlank()) op.setSummary(Words.summary(method.getMethod().getName(), verb));
        if (op.getTags() == null || op.getTags().isEmpty() || op.getTags().equals(List.of(Words.defaultTag(method.getBeanType())))) {
            op.setTags(List.of(Words.resourceTag(path)));
        }

        boolean open = path.contains("/public/");
        if (open) {
            op.setSecurity(List.of()); // no token needed
        } else {
            op.setSecurity(List.of(new SecurityRequirement().addList(OpenApiConfig.BEARER)));
            describePermission(op, method);
        }
        ERRORS.forEach((code, text) -> {
            if (open && (code.equals("401") || code.equals("403"))) return;
            op.getResponses().putIfAbsent(code, problem(text));
        });
        return op;
    }

    private static String fullPath(HandlerMethod m) {
        RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(m.getBeanType(), RequestMapping.class);
        RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(m.getMethod(), RequestMapping.class);
        return join(onClass) + join(onMethod);
    }

    private static HttpMethod httpMethod(HandlerMethod m) {
        RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(m.getMethod(), RequestMapping.class);
        return onMethod == null || onMethod.method().length == 0 ? HttpMethod.GET : HttpMethod.valueOf(onMethod.method()[0].name());
    }

    private static String join(RequestMapping mapping) {
        return mapping == null ? "" : String.join("", mapping.path());
    }

    private static void describePermission(Operation op, HandlerMethod method) {
        RequiresPermission rp = method.hasMethodAnnotation(RequiresPermission.class)
                ? method.getMethodAnnotation(RequiresPermission.class)
                : method.getBeanType().getAnnotation(RequiresPermission.class);
        String line = rp == null ? "Any signed-in user." : "Requires permission " + String.join(" or ", java.util.Arrays.stream(rp.value()).map(v -> "`" + v + "`").toList()) + ".";
        op.setDescription(op.getDescription() == null || op.getDescription().isBlank() ? line : line + "\n\n" + op.getDescription());
    }

    private static ApiResponse problem(String text) {
        return new ApiResponse().description(text).content(new Content().addMediaType("application/problem+json",
                new MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/" + OpenApiConfig.PROBLEM))));
    }
}
