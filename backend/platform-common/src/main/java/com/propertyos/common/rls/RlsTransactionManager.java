package com.propertyos.common.rls;

import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Starts every transaction by writing the caller's identity into PostgreSQL session
 * settings (transaction-local). Row-level security policies read these settings through
 * platform.rls_ok(...), so the database itself filters rows by organisation and scope,
 * even if application code forgets a WHERE clause.
 */
public class RlsTransactionManager extends JpaTransactionManager {
    private static volatile boolean roleSwitch = false;

    /** Called at startup when the connection user is a superuser and must drop privileges per transaction. */
    public static void enableRoleSwitch() {
        roleSwitch = true;
    }

    public RlsTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder == null) return;
        EntityManager em = holder.getEntityManager();
        UserContext ctx = UserContextHolder.get();
        em.unwrap(Session.class).doWork(conn -> {
            if (roleSwitch) {
                try (var st = conn.createStatement()) {
                    st.execute("set local role propertyos_app");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select set_config('app.org_id', ?, true), set_config('app.scope', ?, true), " +
                            "set_config('app.party_id', ?, true), set_config('app.property_ids', ?, true), " +
                            "set_config('app.bypass_rls', ?, true), set_config('app.user_id', ?, true)")) {
                if (ctx == null) {
                    ps.setString(1, "");
                    ps.setString(2, "NONE");
                    ps.setString(3, "");
                    ps.setString(4, "");
                    ps.setString(5, "off");
                    ps.setString(6, "");
                } else {
                    boolean bypass = ctx.isPlatformAdmin() || (ctx.system() && ctx.orgId() == null);
                    ps.setString(1, ctx.orgId() == null ? "" : ctx.orgId().toString());
                    ps.setString(2, ctx.system() ? UserContext.SCOPE_ORG : nz(ctx.scope()));
                    ps.setString(3, ctx.partyId() == null ? "" : ctx.partyId().toString());
                    ps.setString(4, ctx.propertyIds() == null ? "" :
                            ctx.propertyIds().stream().map(UUID::toString).collect(Collectors.joining(",")));
                    ps.setString(5, bypass ? "on" : "off");
                    ps.setString(6, ctx.userId() == null ? "" : ctx.userId().toString());
                }
                ps.execute();
            }
        });
    }

    private static String nz(String s) {
        return s == null ? "NONE" : s;
    }
}
