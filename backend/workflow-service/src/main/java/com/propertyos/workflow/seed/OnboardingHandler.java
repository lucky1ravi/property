package com.propertyos.workflow.seed;

import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

/** When a company is onboarded, gives it the ready-made automation rules so alerts and routing work from day one. */
@Component
public class OnboardingHandler implements EventHandler {
    private final WorkflowSeeder seeder;
    private final TransactionTemplate tx;

    public OnboardingHandler(WorkflowSeeder seeder, PlatformTransactionManager tm) {
        this.seeder = seeder;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public String subscriber() {
        return "workflow.onboarding";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("OrganizationOnboarded");
    }

    @Override
    public void handle(DomainEvent event) {
        UserContextHolder.runAsSystem(event.orgId(), () -> {
            tx.executeWithoutResult(s -> seeder.seedRules(event.orgId()));
            return null;
        });
    }
}
