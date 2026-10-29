package com.propertyos.common.platform;

import java.util.Map;
import com.propertyos.common.rls.RlsTransactionManager;
import com.propertyos.common.security.PermissionInterceptor;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * Shared web and transaction wiring that every service picks up through component scanning.
 */
@Configuration
@EnableScheduling
public class CommonConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(CommonConfig.class);
    private final PermissionInterceptor permissionInterceptor;

    public CommonConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    /**
     * Registers the permission interceptor that enforces {@link RequiresPermission}.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor);
    }

    /**
     * Replaces the default transaction manager with one that sets the row-level-security context at the start of each transaction.
     *
     * @param emf the JPA entity manager factory
     * @return the RLS-aware transaction manager
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new RlsTransactionManager(emf);
    }

    /**
     * Creates the shared platform schema (event log, audit log, RLS function) under an
     * advisory lock, then runs this service's own Flyway migrations.
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return (Flyway flyway) -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            try {
                String sql = new String(new ClassPathResource("platform/platform.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                jdbc.execute((java.sql.Connection conn) -> {
                    try (var st = conn.createStatement()) {
                        st.execute("select pg_advisory_lock(7310042)");
                        try {
                            st.execute(sql);
                        } finally {
                            st.execute("select pg_advisory_unlock(7310042)");
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException("Platform schema initialisation failed", e);
            }
            log.info("Platform schema ready; running service migrations");
            flyway.migrate();
            for (String schema : flyway.getConfiguration().getSchemas()) {
                jdbc.execute("do $$ begin if exists (select 1 from pg_roles where rolname = 'propertyos_app') then " +
                        "grant usage on schema " + schema + " to propertyos_app; " +
                        "grant select, insert, update, delete on all tables in schema " + schema + " to propertyos_app; " +
                        "grant usage, select on all sequences in schema " + schema + " to propertyos_app; " +
                        "end if; end $$");
            }
            // Always use the restricted role when it exists. Do not decide from the connection user's attributes:
            // managed databases such as Amazon RDS give their admin user the ability to bypass row-level security
            // without marking it a superuser, so only a real isolation test (below) can tell.
            Boolean roleExists = jdbc.queryForObject("select exists (select 1 from pg_roles where rolname = 'propertyos_app')", Boolean.class);
            if (Boolean.TRUE.equals(roleExists)) {
                try {
                    jdbc.execute("grant propertyos_app to current_user");
                } catch (Exception ignored) {
                    // already a member, or superuser (implicitly allowed)
                }
                if (canSwitchRole(jdbc)) {
                    RlsTransactionManager.enableRoleSwitch();
                    log.info("Row-level security enforced via SET LOCAL ROLE propertyos_app");
                } else {
                    log.warn("Role propertyos_app exists but this connection cannot switch to it; relying on FORCE ROW LEVEL SECURITY");
                }
            } else {
                log.info("Row-level security enforced via FORCE ROW LEVEL SECURITY (role propertyos_app not available)");
            }
            requireEnforcedIsolation(jdbc);
        };
    }

    /** True when a transaction on this connection can switch to the restricted role. */
    private static boolean canSwitchRole(JdbcTemplate jdbc) {
        try {
            return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection conn) -> {
                boolean auto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (var st = conn.createStatement()) {
                    st.execute("set local role propertyos_app");
                    return true;
                } finally {
                    conn.rollback();
                    conn.setAutoCommit(auto);
                }
            }));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Refuses to start unless the database really keeps one company's rows from another. It does not trust role
     * attributes (managed databases can bypass row-level security without being superusers): it writes a probe row as one
     * company, then checks that a transaction acting for a different company cannot see it. The probe is rolled back.
     *
     * @param jdbc the template to check with
     * @throws IllegalStateException when isolation is not enforced, or could not be verified
     */
    static void requireEnforcedIsolation(JdbcTemplate jdbc) {
        String owner = java.util.UUID.randomUUID().toString();
        String other = java.util.UUID.randomUUID().toString();
        int[] seen = jdbc.execute((java.sql.Connection conn) -> {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                if (RlsTransactionManager.roleSwitchEnabled()) st.execute("set local role propertyos_app");
                st.execute("select set_config('app.bypass_rls', 'off', true), set_config('app.scope', 'ORG', true), set_config('app.org_id', '" + owner + "', true)");
                st.execute("insert into platform.audit_event (org_id, entity_type, action) values ('" + owner + "', 'RlsProbe', 'PROBE')");
                int asOwner = count(st, owner);
                st.execute("select set_config('app.org_id', '" + other + "', true)");
                int asOther = count(st, owner);
                return new int[]{asOwner, asOther};
            } finally {
                conn.rollback();
                conn.setAutoCommit(auto);
            }
        });
        if (seen == null || seen[0] != 1) {
            throw new IllegalStateException("Could not verify row-level security: the probe row was not visible to its own company.");
        }
        if (seen[1] != 0) {
            throw new IllegalStateException("Row-level security is NOT enforced: another company could read this company's rows. The database user bypasses it "
                    + "(a superuser, BYPASSRLS, or a managed-database admin). Create the propertyos_app role so transactions can switch to it, or connect as a plain user.");
        }
        log.info("Row-level security check passed: a second company cannot see the first company's rows");
    }

    private static int count(java.sql.Statement st, String org) throws java.sql.SQLException {
        try (var rs = st.executeQuery("select count(*) from platform.audit_event where entity_type = 'RlsProbe' and org_id = '" + org + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
