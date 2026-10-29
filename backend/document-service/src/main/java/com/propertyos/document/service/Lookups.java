package com.propertyos.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Resolves the record a document is attached to (an application, lease, work order and so on) through the owning
 * service. The document service never trusts links sent by the browser: property, owner, resident and vendor ids are
 * always taken from the source of truth, so row-level security on documents matches the record's own visibility.
 */
@Component
public class Lookups {
    private final ServiceClient client;

    public Lookups(ServiceClient client) {
        this.client = client;
    }

    /** Links for a record: property_id, unit_id, owner_id, tenant_id, vendor_id and a readable label. */
    public record Links(UUID propertyId, String propertyName, UUID unitId, UUID ownerId, UUID tenantId, UUID vendorId, String label) {
        static Links empty(String label) {
            return new Links(null, null, null, null, null, null, label);
        }
    }

    public static final Set<String> ENTITY_TYPES = Set.of("APPLICATION", "LEASE", "WORK_ORDER", "INVOICE", "STATEMENT", "PROPERTY", "OWNER", "VENDOR", "TENANT");

    public Links resolve(String type, UUID id) {
        if (type == null || id == null) return Links.empty(null);
        if (!ENTITY_TYPES.contains(type)) throw ApiException.badRequest("Documents can't be attached to " + type);
        try {
            return switch (type) {
                case "APPLICATION" -> from(client.get(ServiceClient.Service.LEASING, "/internal/leasing/applications/" + id + "/brief"), "label");
                case "LEASE" -> from(client.get(ServiceClient.Service.LEASING, "/internal/leasing/leases/" + id + "/brief"), "label");
                case "TENANT" -> from(client.get(ServiceClient.Service.LEASING, "/internal/leasing/tenants/" + id + "/brief"), "label");
                case "PROPERTY" -> {
                    JsonNode p = client.get(ServiceClient.Service.LEASING, "/internal/leasing/property/" + id);
                    yield new Links(id, p.path("name").asText(), null, uuid(p, "owner_id"), null, null, p.path("name").asText());
                }
                case "OWNER" -> {
                    JsonNode o = client.get(ServiceClient.Service.LEASING, "/internal/leasing/owners/" + id);
                    yield new Links(null, null, null, id, null, null, o.path("name").asText());
                }
                case "WORK_ORDER" -> from(client.get(ServiceClient.Service.MAINTENANCE, "/internal/maintenance/work-orders/" + id + "/brief"), "label");
                case "VENDOR" -> from(client.get(ServiceClient.Service.MAINTENANCE, "/internal/maintenance/vendors/" + id + "/brief"), "label");
                case "INVOICE" -> from(client.get(ServiceClient.Service.BILLING, "/internal/billing/invoices/" + id + "/brief"), "label");
                case "STATEMENT" -> from(client.get(ServiceClient.Service.BILLING, "/internal/billing/statements/" + id + "/brief"), "label");
                default -> Links.empty(null);
            };
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("The " + type.toLowerCase().replace('_', ' ') + " this file belongs to could not be found.");
        }
    }

    private static Links from(JsonNode n, String labelField) {
        if (n == null || n.isNull() || n.isEmpty()) throw ApiException.notFound("Linked record");
        return new Links(uuid(n, "property_id"), n.path("property_name").asText(null), uuid(n, "unit_id"), uuid(n, "owner_id"), uuid(n, "tenant_id"), uuid(n, "vendor_id"), n.path(labelField).asText(null));
    }

    static UUID uuid(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || v.asText().isBlank()) return null;
        try {
            return UUID.fromString(v.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /** The resident's current lease (for files a resident uploads without naming a record). */
    public Optional<JsonNode> tenantLease(UUID tenantId) {
        try {
            return Optional.ofNullable(client.get(ServiceClient.Service.LEASING, "/internal/leasing/tenant-lease/" + tenantId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** People who could be asked to sign: everyone active in the organisation with their party link. */
    public List<JsonNode> directory() {
        List<JsonNode> out = new ArrayList<>();
        try {
            for (JsonNode u : client.get(ServiceClient.Service.IDENTITY, "/internal/directory")) out.add(u);
        } catch (Exception ignored) {
        }
        return out;
    }

    public JsonNode list(ServiceClient.Service s, String path) {
        try {
            JsonNode n = client.get(s, path);
            return n == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode() : n;
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        }
    }

    /** Keeps the vendor's insurance date in maintenance in step with the newest certificate on file. */
    public void syncVendorInsurance(UUID vendorId, java.time.LocalDate expires) {
        try {
            client.post(ServiceClient.Service.MAINTENANCE, "/internal/maintenance/vendors/" + vendorId + "/insurance", Map.of("expiresOn", expires.toString()));
        } catch (Exception ignored) {
        }
    }
}
