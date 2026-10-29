package com.propertyos.common.platform;

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
            Boolean canSwitch = jdbc.queryForObject(
                    "select exists (select 1 from pg_roles where rolname = 'propertyos_app') " +
                            "and (select rolsuper or rolbypassrls from pg_roles where rolname = current_user)", Boolean.class);
            if (Boolean.TRUE.equals(canSwitch)) {
                try {
                    jdbc.execute("grant propertyos_app to current_user");
                } catch (Exception ignored) {
                    // already a member, or superuser (implicitly allowed)
                }
                RlsTransactionManager.enableRoleSwitch();
                log.info("Row-level security enforced via SET LOCAL ROLE propertyos_app");
            } else {
                log.info("Row-level security enforced via FORCE ROW LEVEL SECURITY (non-superuser connection)");
            }
        };
    }
}
