package com.propertyos.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Delivers events from the platform log to this service's handlers, at least once. */
@Component
public class EventPoller {
    private static final Logger log = LoggerFactory.getLogger(EventPoller.class);
    private final List<EventHandler> handlers;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public EventPoller(List<EventHandler> handlers, JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager tm) {
        this.handlers = handlers;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${propertyos.events.poll-ms:1500}", initialDelay = 5000)
    public void poll() {
        for (EventHandler h : handlers) {
            try {
                drain(h);
            } catch (Exception e) {
                log.warn("Event polling failed for {}: {}", h.subscriber(), e.getMessage());
            }
        }
    }

    private void drain(EventHandler h) {
        jdbc.update("insert into platform.consumer_offset (subscriber, last_id) values (?, 0) on conflict do nothing", h.subscriber());
        Long offset = jdbc.queryForObject("select last_id from platform.consumer_offset where subscriber = ?", Long.class, h.subscriber());
        boolean all = h.eventTypes().contains("*");
        String typeFilter = all ? "" : " and event_type = any (?)";
        Object[] args = all ? new Object[]{offset} : new Object[]{offset, h.eventTypes().toArray(new String[0])};
        List<DomainEvent> events = jdbc.query(
                "select * from platform.event_log where id > ?" + typeFilter + " order by id limit 200",
                (rs, i) -> {
                    try {
                        Timestamp ts = rs.getTimestamp("created_at");
                        return new DomainEvent(rs.getLong("id"), (UUID) rs.getObject("org_id"), rs.getString("event_type"),
                                rs.getString("aggregate_type"), (UUID) rs.getObject("aggregate_id"),
                                mapper.readTree(rs.getString("payload")), (UUID) rs.getObject("actor_id"),
                                rs.getString("actor_name"), ts.toInstant().atOffset(ZoneOffset.UTC));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, args);
        long last = offset;
        for (DomainEvent e : events) {
            try {
                UserContextHolder.runAs(com.propertyos.common.security.UserContext.system(e.orgId()),
                        (Runnable) () -> tx.executeWithoutResult(s -> h.handle(e)));
            } catch (Exception ex) {
                log.error("Handler {} failed on event {} ({}): {}", h.subscriber(), e.id(), e.type(), ex.getMessage());
                jdbc.update("insert into platform.dead_letter (subscriber, event_id, error) values (?, ?, ?)",
                        h.subscriber(), e.id(), String.valueOf(ex.getMessage()));
            }
            last = e.id();
            jdbc.update("update platform.consumer_offset set last_id = ?, updated_at = now() where subscriber = ?", last, h.subscriber());
        }
    }
}
