package com.propertyos.identity.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every feature and action that can be granted in the role builder. */
public final class FeatureCatalog {
    private FeatureCatalog() {
    }

    public record Feature(String key, String label, String module, List<String> actions, boolean platformOnly) {
    }

    public static final List<Feature> FEATURES = List.of(
            new Feature("DASHBOARD", "Dashboards", "General", List.of("VIEW"), false),
            new Feature("PROPERTY", "Properties and units", "Portfolio", List.of("VIEW", "CREATE", "EDIT", "DELETE"), false),
            new Feature("LISTING", "Listings", "Leasing", List.of("VIEW", "CREATE", "EDIT", "APPROVE", "DELETE"), false),
            new Feature("SHOWING", "Showings", "Leasing", List.of("VIEW", "CREATE", "EDIT"), false),
            new Feature("APPLICATION", "Applications", "Leasing", List.of("VIEW", "CREATE", "EDIT", "APPROVE"), false),
            new Feature("LEASE", "Leases and renewals", "Leasing", List.of("VIEW", "CREATE", "EDIT", "SIGN", "APPROVE"), false),
            new Feature("INVOICE", "Invoices and charges", "Payments", List.of("VIEW", "CREATE", "EDIT", "APPROVE", "EXPORT"), false),
            new Feature("PAYMENT", "Payments, fees and refunds", "Payments", List.of("VIEW", "CREATE", "APPROVE", "EXPORT"), false),
            new Feature("OWNER_STATEMENT", "Owner statements and payouts", "Payments", List.of("VIEW", "CREATE", "EXPORT"), false),
            new Feature("WORK_ORDER", "Work orders", "Maintenance", List.of("VIEW", "CREATE", "EDIT", "UPDATE", "APPROVE", "VERIFY"), false),
            new Feature("VENDOR", "Vendors", "Maintenance", List.of("VIEW", "CREATE", "EDIT"), false),
            new Feature("AUTOMATION", "Automation rules", "Automation", List.of("VIEW", "CONFIGURE"), false),
            new Feature("WORKFLOW", "Workflow designer", "Automation", List.of("VIEW", "CONFIGURE"), false),
            new Feature("OPPORTUNITY", "Opportunities and profit insights", "Insights", List.of("VIEW"), false),
            new Feature("REPORT", "Reports", "Insights", List.of("VIEW", "EXPORT"), false),
            new Feature("AI_ASSISTANT", "AI assistant", "AI", List.of("VIEW"), false),
            new Feature("USER", "Users", "Administration", List.of("VIEW", "CREATE", "EDIT", "DELETE"), false),
            new Feature("ROLE", "Roles and permissions", "Administration", List.of("VIEW", "CONFIGURE"), false),
            new Feature("SETTINGS", "Product configuration", "Administration", List.of("VIEW", "CONFIGURE"), false),
            new Feature("AUDIT", "Audit log", "Administration", List.of("VIEW"), false),
            new Feature("PLATFORM", "Platform administration", "Platform", List.of("VIEW", "CONFIGURE"), true)
    );

    public static final List<String> ACTIONS = List.of("VIEW", "CREATE", "EDIT", "UPDATE", "SIGN", "APPROVE", "VERIFY", "DELETE", "EXPORT", "CONFIGURE");

    public static final Map<String, String> SCOPES = new LinkedHashMap<>();

    static {
        SCOPES.put("ORG", "Whole organisation");
        SCOPES.put("PROPERTY", "Assigned properties");
        SCOPES.put("OWNER", "Own properties (owners)");
        SCOPES.put("TENANT", "Own lease (residents)");
        SCOPES.put("VENDOR", "Assigned jobs (vendors)");
    }

    /** Fields that support visible / masked / hidden rules. */
    public static final List<Map<String, String>> FIELDS = List.of(
            Map.of("key", "Applicant.ssn", "label", "Applicant SSN"),
            Map.of("key", "Applicant.income", "label", "Applicant income"),
            Map.of("key", "Applicant.dateOfBirth", "label", "Applicant date of birth"),
            Map.of("key", "Owner.bankAccount", "label", "Owner bank account"),
            Map.of("key", "Tenant.phone", "label", "Resident phone"),
            Map.of("key", "Tenant.email", "label", "Resident email"),
            Map.of("key", "Payment.method", "label", "Payment method details"),
            Map.of("key", "Vendor.rates", "label", "Vendor rates and cost history")
    );

    public static boolean valid(String feature, String action) {
        return FEATURES.stream().anyMatch(f -> f.key().equals(feature) && f.actions().contains(action));
    }
}
