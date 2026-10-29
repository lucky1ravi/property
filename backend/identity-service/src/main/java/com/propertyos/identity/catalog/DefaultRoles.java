package com.propertyos.identity.catalog;

import java.math.BigDecimal;
import java.util.*;

/** Editable default roles created for every new organisation. */
public final class DefaultRoles {
    private DefaultRoles() {
    }

    /**
     * One permission a role holds.
     *
     * @param feature   the feature key, for example {@code LEASE}
     * @param action    the action, for example {@code APPROVE}
     * @param maxAmount largest amount the holder may approve, or null for no limit
     */
    public record Grant(String feature, String action, BigDecimal maxAmount) {
    }

    /**
     * A built-in role that is copied into every new company.
     *
     * @param key         stable role key
     * @param name        display name
     * @param description what the role is for
     * @param scope       {@code ORG} or {@code PLATFORM}
     * @param color       badge colour
     * @param grants      permissions the role starts with
     * @param fieldRules  per-field visibility rules, for example masking bank details
     */
    public record RoleTemplate(String key, String name, String description, String scope, String color,
                               List<Grant> grants, Map<String, String> fieldRules) {
    }

    private static List<Grant> all(String... excludedFeatures) {
        Set<String> ex = Set.of(excludedFeatures);
        List<Grant> g = new ArrayList<>();
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            if (f.platformOnly() || ex.contains(f.key())) continue;
            for (String a : f.actions()) g.add(new Grant(f.key(), a, null));
        }
        return g;
    }

    /** Parse "FEATURE:A,B,C" specs; "FEATURE:APPROVE<=1000" sets a limit. */
    private static List<Grant> grants(String... specs) {
        List<Grant> g = new ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":");
            for (String a : parts[1].split(",")) {
                BigDecimal limit = null;
                String action = a;
                if (a.contains("<=")) {
                    action = a.substring(0, a.indexOf("<="));
                    limit = new BigDecimal(a.substring(a.indexOf("<=") + 2));
                }
                g.add(new Grant(parts[0], action, limit));
            }
        }
        return g;
    }

    /**
     * Built-in roles copied into every new company.
     */
    public static final List<RoleTemplate> TEMPLATES = List.of(
            new RoleTemplate("ORG_ADMIN", "Org Admin", "Full control of the company account, users, roles and configuration.",
                    "ORG", "#0B2545", all(), Map.of()),
            new RoleTemplate("PROPERTY_MANAGER", "Property Manager", "Runs operations across the whole portfolio; approves repairs up to $5,000.",
                    "ORG", "#13315C", withLimit(all("USER", "ROLE", "SETTINGS"), "WORK_ORDER", "APPROVE", "5000",
                    grants("SETTINGS:VIEW", "USER:VIEW", "ROLE:VIEW")), Map.of("Owner.bankAccount", "MASKED")),
            new RoleTemplate("BROKER", "Broker", "Manages an assigned portfolio end to end: leasing, residents, repairs up to $1,000.",
                    "PROPERTY", "#1D4E89",
                    grants("DASHBOARD:VIEW", "PROPERTY:VIEW,EDIT", "OWNER:VIEW", "LISTING:VIEW,CREATE,EDIT,APPROVE", "SHOWING:VIEW,CREATE,EDIT",
                            "APPLICATION:VIEW,EDIT,APPROVE", "LEASE:VIEW,CREATE,EDIT,APPROVE", "INVOICE:VIEW", "PAYMENT:VIEW",
                            "OWNER_STATEMENT:VIEW", "WORK_ORDER:VIEW,CREATE,EDIT,APPROVE<=1000,VERIFY", "VENDOR:VIEW",
                            "AUTOMATION:VIEW", "WORKFLOW:VIEW", "OPPORTUNITY:VIEW", "REPORT:VIEW", "AI_ASSISTANT:VIEW", "CONVERSATION:VIEW,CREATE", "ALERT:VIEW",
                            "DOCUMENT:VIEW,CREATE,EDIT,EXPORT", "ESIGN:VIEW,CREATE,SIGN", "MEDIA:UPLOAD"),
                    Map.of("Applicant.ssn", "MASKED", "Owner.bankAccount", "HIDDEN", "Document.sensitive", "VISIBLE")),
            new RoleTemplate("LEASING_AGENT", "Leasing Agent", "Handles listings, showings and applications; cannot approve.",
                    "PROPERTY", "#3E7CB1",
                    grants("DASHBOARD:VIEW", "PROPERTY:VIEW", "OWNER:VIEW", "LISTING:VIEW,CREATE,EDIT", "SHOWING:VIEW,CREATE,EDIT",
                            "APPLICATION:VIEW,EDIT", "LEASE:VIEW", "AI_ASSISTANT:VIEW", "CONVERSATION:VIEW,CREATE",
                            "DOCUMENT:VIEW,CREATE,EDIT", "ESIGN:VIEW", "MEDIA:UPLOAD"),
                    Map.of("Applicant.ssn", "HIDDEN", "Applicant.income", "MASKED", "Owner.bankAccount", "HIDDEN", "Document.sensitive", "MASKED")),
            new RoleTemplate("ACCOUNTANT", "Accountant", "Owns receivables, payables, owner statements and reconciliation.",
                    "ORG", "#2A6F4E",
                    grants("DASHBOARD:VIEW", "PROPERTY:VIEW", "OWNER:VIEW", "LEASE:VIEW", "INVOICE:VIEW,CREATE,EDIT,APPROVE,EXPORT",
                            "PAYMENT:VIEW,CREATE,APPROVE,EXPORT", "OWNER_STATEMENT:VIEW,CREATE,EXPORT", "VENDOR:VIEW",
                            "WORK_ORDER:VIEW", "REPORT:VIEW,EXPORT", "AI_ASSISTANT:VIEW", "AUDIT:VIEW", "TAX_REPORT:VIEW,EDIT,EXPORT", "ALERT:VIEW", "CONVERSATION:VIEW,CREATE",
                            "DOCUMENT:VIEW,CREATE,EDIT,EXPORT", "ESIGN:VIEW,CREATE,SIGN", "MEDIA:UPLOAD"),
                    Map.of("Applicant.ssn", "HIDDEN", "Document.sensitive", "VISIBLE")),
            new RoleTemplate("OWNER", "Property Owner", "Sees only their own properties; approves repairs over their limit.",
                    "OWNER", "#C8963E",
                    grants("DASHBOARD:VIEW", "PROPERTY:VIEW", "OWNER:VIEW", "LEASE:VIEW", "APPLICATION:VIEW,APPROVE", "INVOICE:VIEW",
                            "PAYMENT:VIEW", "OWNER_STATEMENT:VIEW,EXPORT", "WORK_ORDER:VIEW,APPROVE", "REPORT:VIEW",
                            "OPPORTUNITY:VIEW", "AI_ASSISTANT:VIEW", "CONVERSATION:VIEW,CREATE", "DOCUMENT:VIEW,CREATE", "ESIGN:VIEW,SIGN", "MEDIA:UPLOAD"),
                    Map.of("Applicant.ssn", "HIDDEN", "Tenant.phone", "MASKED", "Vendor.rates", "HIDDEN")),
            new RoleTemplate("TENANT", "Resident", "Sees only their own lease, balance and requests.",
                    "TENANT", "#5C6B73",
                    grants("DASHBOARD:VIEW", "LEASE:VIEW,SIGN", "INVOICE:VIEW", "PAYMENT:VIEW,CREATE",
                            "WORK_ORDER:VIEW,CREATE,VERIFY", "AI_ASSISTANT:VIEW", "CONVERSATION:VIEW,CREATE", "DOCUMENT:VIEW,CREATE", "ESIGN:VIEW,SIGN", "MEDIA:UPLOAD"),
                    Map.of("Owner.bankAccount", "HIDDEN", "Vendor.rates", "HIDDEN")),
            new RoleTemplate("VENDOR", "Vendor", "Sees only jobs assigned to their company.",
                    "VENDOR", "#8B5E34",
                    grants("DASHBOARD:VIEW", "WORK_ORDER:VIEW,UPDATE", "CONVERSATION:VIEW,CREATE", "DOCUMENT:VIEW,CREATE", "ESIGN:VIEW,SIGN", "MEDIA:UPLOAD"),
                    Map.of("Tenant.email", "HIDDEN", "Owner.bankAccount", "HIDDEN", "Payment.method", "HIDDEN"))
    );

    /**
     * The platform operator's role. It manages companies and plans but sees no company data.
     */
    public static final RoleTemplate PLATFORM_ADMIN = new RoleTemplate("PLATFORM_ADMIN", "Platform Admin",
            "PropertyOS operator: organisations, plans, feature toggles and platform health.", "PLATFORM", "#000000",
            grants("PLATFORM:VIEW,CONFIGURE", "DASHBOARD:VIEW", "SETTINGS:VIEW,CONFIGURE", "AUDIT:VIEW"), Map.of());

    private static List<Grant> withLimit(List<Grant> base, String feature, String action, String limit, List<Grant> extra) {
        List<Grant> out = new ArrayList<>();
        for (Grant g : base) {
            if (g.feature().equals(feature) && g.action().equals(action)) out.add(new Grant(feature, action, new BigDecimal(limit)));
            else out.add(g);
        }
        out.addAll(extra);
        return out;
    }

    /** Features added after first release. Existing organisations get them once, from the template grants, so the Roles screen stays in step. */
    public static final List<String> SYNCED_FEATURES = List.of("TAX_REPORT", "ALERT", "CONVERSATION", "DOCUMENT", "ESIGN", "MEDIA", "OWNER");

    /** Field rules added after first release, copied once to existing default roles that have no rule for the field yet. */
    public static final List<String> SYNCED_FIELDS = List.of("Document.sensitive");

    /**
     * Finds a built-in role by key.
     *
     * @param key the role key
     * @return the template, or empty when unknown
     */
    public static Optional<RoleTemplate> byKey(String key) {
        if (PLATFORM_ADMIN.key().equals(key)) return Optional.of(PLATFORM_ADMIN);
        return TEMPLATES.stream().filter(t -> t.key().equals(key)).findFirst();
    }
}
