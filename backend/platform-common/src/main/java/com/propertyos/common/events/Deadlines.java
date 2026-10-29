package com.propertyos.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.config.ConfigClient;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Shared helpers for deadline alerts: lease expiry, deposit refunds, vendor insurance and tax dates. */
public final class Deadlines {
    private Deadlines() {
    }

    public static final String OVERDUE = "overdue";

    /** One upcoming date. {@code audiences} is who is told ({type, target} pairs as used by the notification audience resolver). */
    public static Map<String, Object> item(String type, String title, String detail, LocalDate due, String link, String entityId,
                                           List<String[]> audiences) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("detail", detail);
        m.put("due_date", due);
        m.put("days_left", due == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), due));
        m.put("link", link);
        m.put("entity_id", entityId);
        m.put("audiences", audiences);
        return m;
    }

    public static List<Integer> thresholds(ConfigClient cfg, String path, List<Integer> fallback) {
        JsonNode n = cfg.at(path);
        if (!n.isArray() || n.isEmpty()) return fallback;
        List<Integer> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asInt()));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** The reminder this item has reached: the smallest threshold at or above the days left, "overdue" once past, or null when it is still too early. */
    public static String bucket(Long daysLeft, List<Integer> thresholds) {
        if (daysLeft == null) return null;
        if (daysLeft < 0) return OVERDUE;
        for (int t : thresholds) if (daysLeft <= t) return String.valueOf(t);
        return null;
    }

    /** Sends the alert to every audience once per reminder bucket. */
    @SuppressWarnings("unchecked")
    public static int notifyAll(Notifier notifier, Map<String, Object> item, String bucket, List<Integer> thresholds) {
        if (bucket == null) return 0;
        boolean urgent = OVERDUE.equals(bucket) || (!thresholds.isEmpty() && Integer.parseInt(bucket) <= thresholds.get(0));
        Long days = (Long) item.get("days_left");
        String when = days == null ? "" : days < 0 ? " (" + (-days) + " days overdue)" : days == 0 ? " (today)" : " (in " + days + " days)";
        int sent = 0;
        for (String[] a : (List<String[]>) item.get("audiences")) {
            notifier.sendAlert(a[0], a[1], String.valueOf(item.get("title")), item.get("detail") + when, String.valueOf(item.get("link")),
                    urgent ? "HIGH" : "NORMAL", "COMPLIANCE", item.get("type") + ":" + item.get("entity_id") + ":" + bucket + ":" + a[0] + ":" + a[1]);
            sent++;
        }
        return sent;
    }

    /** The list shown on the deadlines page: the audience lists are internal and stay out of the response. */
    public static List<Map<String, Object>> publicView(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> i : items) {
            Map<String, Object> m = new LinkedHashMap<>(i);
            m.remove("audiences");
            Object d = m.get("days_left");
            long days = d == null ? 9999 : (Long) d;
            m.put("severity", days < 0 ? "OVERDUE" : days <= 14 ? "HIGH" : days <= 45 ? "MEDIUM" : "LOW");
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> m.get("days_left") == null ? Long.MIN_VALUE + 1 : (Long) m.get("days_left")));
        return out;
    }
}
