package com.propertyos.leasing.service;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The catalogue of amenities a property can offer, each with an icon. The platform administrator keeps the list every company
 * starts from; a company adds its own (a "Dog wash", a "Business centre") and manages only those. A property stores the amenity's
 * name, so removing or renaming a catalogue entry never changes a property that already has it.
 */
@Service
public class AmenityService {
    private final Db db;
    private final AuditService audit;

    public AmenityService(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Everything the caller may pick: the platform's amenities and their own company's. Platform administrators see the platform list only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> available() {
        return db.list("select id, name, icon, active, org_id is null as platform from leasing.amenity where active order by lower(name)");
    }

    /** The company's own amenities, or (for the platform administrator) the platform-wide ones, including switched-off ones. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> managed(boolean platform) {
        return db.list("select id, name, icon, active, org_id is null as platform, created_by, created_at from leasing.amenity where " + (platform ? "org_id is null" : "org_id is not null")
                + " order by lower(name)");
    }

    private static String name(Map<String, Object> b) {
        String n = Req.required(b, "name").trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 60) throw ApiException.badRequest("The amenity's name needs 2 to 60 characters.");
        return n;
    }

    private static String icon(Map<String, Object> b) {
        String i = Req.required(b, "icon").trim();
        if (!i.matches("[A-Za-z][A-Za-z0-9]{1,39}")) throw ApiException.badRequest("Choose an icon from the list.");
        return i;
    }

    /**
     * Adds an amenity.
     *
     * @param platform true for the platform-wide list (platform administrators), false for the signed-in company's own
     * @throws ApiException (409) when that name already exists for the company or on the platform list
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> b, boolean platform) {
        String n = name(b), ic = icon(b);
        UUID org = platform ? null : UserContextHolder.orgId();
        // A company may not repeat a name the platform already offers, so the picker never shows two of the same.
        Long same = db.value(Long.class, "select count(*) from leasing.amenity where lower(name) = lower(?) and (org_id is null or org_id = ?)", n, org);
        if (same != null && same > 0) throw ApiException.conflict("There is already an amenity called \"" + n + "\".");
        UUID id = UUID.randomUUID();
        db.update("insert into leasing.amenity (id, org_id, name, icon, created_by) values (?,?,?,?,?)", id, org, n, ic, UserContextHolder.require().name());
        audit.record("Amenity", id, "AMENITY_ADDED", null, null, Map.of("name", n, "icon", ic, "scope", platform ? "PLATFORM" : "COMPANY"));
        return Map.of("id", id, "name", n, "icon", ic, "platform", platform);
    }

    /** Changes an amenity's icon, name or availability. Only the owner of the entry may: a company cannot edit the platform's, nor the platform a company's. */
    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> b, boolean platform) {
        Map<String, Object> cur = db.one("Amenity", "select id, org_id, name, icon, active from leasing.amenity where id = ?", id);
        if ((cur.get("org_id") == null) != platform) throw ApiException.forbidden(platform ? "That is a company's own amenity." : "That amenity belongs to the platform list. Ask the platform administrator to change it.");
        String n = b.containsKey("name") ? name(b) : (String) cur.get("name");
        String ic = b.containsKey("icon") ? icon(b) : (String) cur.get("icon");
        boolean active = b.containsKey("active") ? Boolean.TRUE.equals(b.get("active")) : (Boolean) cur.get("active");
        if (!n.equalsIgnoreCase((String) cur.get("name"))) {
            Long same = db.value(Long.class, "select count(*) from leasing.amenity where lower(name) = lower(?) and id <> ? and (org_id is null or org_id = ?)", n, id, cur.get("org_id"));
            if (same != null && same > 0) throw ApiException.conflict("There is already an amenity called \"" + n + "\".");
        }
        db.update("update leasing.amenity set name = ?, icon = ?, active = ?, updated_at = now() where id = ?", n, ic, active, id);
        audit.record("Amenity", id, "AMENITY_CHANGED", null, null, Map.of("name", n, "icon", ic, "active", active));
        return Map.of("id", id, "name", n, "icon", ic, "active", active);
    }

    /** Removes an amenity from the catalogue. Properties that already have it keep it. */
    @Transactional
    public void delete(UUID id, boolean platform) {
        Map<String, Object> cur = db.one("Amenity", "select id, org_id, name from leasing.amenity where id = ?", id);
        if ((cur.get("org_id") == null) != platform) throw ApiException.forbidden(platform ? "That is a company's own amenity." : "That amenity belongs to the platform list. Ask the platform administrator to change it.");
        db.update("delete from leasing.amenity where id = ?", id);
        audit.record("Amenity", id, "AMENITY_REMOVED", null, null, Map.of("name", String.valueOf(cur.get("name"))));
    }
}
