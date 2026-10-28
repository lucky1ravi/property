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

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
