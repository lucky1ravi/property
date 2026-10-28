package com.propertyos.ai.service;

import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Every model interaction is recorded: who asked, what tools ran, which model answered, and whether a guardrail blocked it. */
@Component
public class AiAudit {
    private final Db db;
    private final Guardrails guardrails;

    public AiAudit(Db db, Guardrails guardrails) {
        this.db = db;
        this.guardrails = guardrails;
    }

    @Transactional
    public void record(String kind, String capability, String prompt, List<String> tools, String provider, int in, int out, boolean blocked, String reason, boolean fallback, long ms) {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null || ctx.orgId() == null) return;
        db.update("""
                insert into ai.ai_audit (id, org_id, user_id, user_name, kind, capability, prompt, tools_used, provider, input_tokens, output_tokens, blocked, block_reason, fallback, latency_ms)
                values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)""",
                UUID.randomUUID(), ctx.orgId(), ctx.userId(), ctx.name(), kind, capability, trim(guardrails.redact(prompt), 1900), db.json(tools), provider, in, out, blocked, reason, fallback, (int) ms);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>(db.one("Summary", """
                select count(*) as interactions, count(*) filter (where blocked) as blocked, count(*) filter (where fallback) as fallbacks,
                       coalesce(sum(input_tokens + output_tokens), 0) as tokens, coalesce(round(avg(latency_ms)), 0) as avg_latency_ms
                from ai.ai_audit where created_at > now() - interval '30 days'"""));
        s.put("recent", db.list("select id, user_name, kind, capability, prompt, tools_used, provider, blocked, block_reason, fallback, latency_ms, created_at from ai.ai_audit order by created_at desc limit 40"));
        return s;
    }

    private static String trim(String s, int n) {
        return s == null ? null : s.length() > n ? s.substring(0, n) : s;
    }
}
