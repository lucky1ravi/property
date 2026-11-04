package com.propertyos.leasing.service;

import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** Starts a listing boost when the platform invoice it was bought with is paid in full. */
@Component
public class BoostEvents implements EventHandler {
    private final BoostService boosts;

    public BoostEvents(BoostService boosts) {
        this.boosts = boosts;
    }

    @Override
    public String subscriber() {
        return "leasing.boost-paid";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("PlatformInvoicePaid");
    }

    /** Only invoices of kind BOOST matter here; any other invoice is ignored. */
    @Override
    public void handle(DomainEvent e) {
        if (!"BOOST".equals(e.text("kind"))) return;
        UUID invoice = e.uuid("invoiceId");
        if (invoice != null) boosts.onInvoicePaid(invoice);
    }
}
