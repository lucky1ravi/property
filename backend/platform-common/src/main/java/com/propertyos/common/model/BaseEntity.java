package com.propertyos.common.model;

import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Every business record belongs to one organisation; the column drives row-level security. */
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (orgId == null) {
            UserContext ctx = UserContextHolder.get();
            if (ctx != null) orgId = ctx.orgId();
        }
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Returns the row's unique identifier.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Sets the row's unique identifier.
     *
     * @param id the identifier
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Returns the company (tenant) that owns this row.
     */
    public UUID getOrgId() {
        return orgId;
    }

    /**
     * Sets the owning company (tenant).
     *
     * @param orgId the company identifier
     */
    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    /**
     * Returns when the row was created.
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns when the row was last changed.
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
