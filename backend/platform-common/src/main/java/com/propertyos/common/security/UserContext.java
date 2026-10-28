package com.propertyos.common.security;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling and what they may do. Built from the JWT on every request and
 * pushed into PostgreSQL session settings so row-level security can filter rows.
 *
 * @param scope       data scope: PLATFORM, ORG, PROPERTY, OWNER, TENANT or VENDOR
 * @param partyId     the owner, tenant or vendor record this user represents (if any)
 * @param propertyIds properties assigned to the user when scope is PROPERTY
 * @param limits      approval limits by key, for example WORK_ORDER:APPROVE -> 1000
 * @param fieldRules  per-field visibility, for example "Applicant.ssn" -> MASKED or HIDDEN
 * @param system      true for internal service-to-service and scheduled work
 */
public record UserContext(
        UUID userId,
        UUID orgId,
        String name,
        String email,
        List<String> roles,
        Set<String> permissions,
        String scope,
        UUID partyId,
        List<UUID> propertyIds,
        Map<String, BigDecimal> limits,
        Map<String, String> fieldRules,
        boolean system
) {
    public static final String SCOPE_PLATFORM = "PLATFORM";
    public static final String SCOPE_ORG = "ORG";
    public static final String SCOPE_PROPERTY = "PROPERTY";
    public static final String SCOPE_OWNER = "OWNER";
    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_VENDOR = "VENDOR";

    public boolean isPlatformAdmin() {
        return SCOPE_PLATFORM.equals(scope);
    }

    /** True when the caller holds FEATURE:ACTION (or FEATURE:* / *:*). */
    public boolean has(String permission) {
        if (system || isPlatformAdmin()) return true;
        if (permissions == null) return false;
        if (permissions.contains(permission) || permissions.contains("*:*")) return true;
        int idx = permission.indexOf(':');
        return idx > 0 && permissions.contains(permission.substring(0, idx) + ":*");
    }

    /** Approval limit for a permission, or null when unlimited. */
    public BigDecimal limit(String key) {
        if (system || isPlatformAdmin()) return null;
        return limits == null ? null : limits.get(key);
    }

    /** VISIBLE, MASKED or HIDDEN for a field such as "Applicant.ssn". */
    public String fieldAccess(String field) {
        if (system || isPlatformAdmin() || fieldRules == null) return "VISIBLE";
        return fieldRules.getOrDefault(field, "VISIBLE");
    }

    public boolean isScope(String s) {
        return s.equals(scope);
    }

    public static UserContext system(UUID orgId) {
        return new UserContext(null, orgId, "System", "system@propertyos.local", List.of("SYSTEM"),
                Set.of("*:*"), orgId == null ? SCOPE_PLATFORM : SCOPE_ORG, null, List.of(), Map.of(), Map.of(), true);
    }
}
