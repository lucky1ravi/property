package com.propertyos.common.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation shared by every service: the document header, the bearer-token security scheme and the
 * error schema that all endpoints use. Per-operation detail comes from {@link OperationDocumenter}.
 */
@Configuration
public class OpenApiConfig {
    /**
     * Name of the bearer-token security scheme in the OpenAPI document.
     */
    public static final String BEARER = "bearerAuth";
    /**
     * Name of the shared error schema in the OpenAPI document.
     */
    public static final String PROBLEM = "Problem";

    /**
     * Builds the OpenAPI description for this service: title, version and the bearer-token security scheme.
     *
     * @param service the service name
     * @param version the API version shown in Swagger
     */
    @Bean
    public OpenAPI propertyOsOpenApi(@Value("${spring.application.name:propertyos}") String service,
                                     @Value("${propertyos.docs.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("PropertyOS " + service)
                        .version(version)
                        .description("""
                                Part of PropertyOS, a property management platform for the US market.

                                **Authentication.** Sign in with `POST /api/identity/public/login` and send the token as
                                `Authorization: Bearer <token>`. Endpoints under `/public/` need no token.

                                **Permissions.** Each operation lists the permission it needs (for example `LEASE:APPROVE`).
                                Calls are also limited to the caller's own company by database row-level security.

                                **Errors.** Every failure is an RFC 7807 `application/problem+json` body with a stable `code`.
                                """)
                        .contact(new Contact().name("PropertyOS engineering")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("User token from the sign-in endpoint."))
                        .addSchemas(PROBLEM, problemSchema()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    private static Schema<?> problemSchema() {
        return new Schema<>().type("object").description("RFC 7807 problem details returned for every error.")
                .addProperty("title", new StringSchema().example("Forbidden"))
                .addProperty("status", new IntegerSchema().example(403))
                .addProperty("detail", new StringSchema().example("You don't have permission to do this (LEASE:APPROVE)"))
                .addProperty("instance", new StringSchema().example("/api/leasing/leases/1"))
                .addProperty("code", new StringSchema().description("Stable machine-readable code").example("FORBIDDEN"));
    }
}
