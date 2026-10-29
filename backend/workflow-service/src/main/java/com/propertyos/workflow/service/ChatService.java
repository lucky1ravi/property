package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Conversations between residents, owners, vendors and staff, tied to a record (work order, invoice, lease...).
 * Who may message whom is set in the company's "chat" configuration and limited to people connected through the same properties.
 * Only members can read a conversation.
 */
@Service
public class ChatService {
    private static final Set<String> CONTEXTS = Set.of("GENERAL", "WORK_ORDER", "INVOICE", "LEASE", "APPLICATION", "PROPERTY");

    private final Db db;
    private final ServiceClient client;
    private final ConfigClient config;
    private final Notifier notifier;
    private final AiClient ai;
    private final ObjectMapper mapper;

    public ChatService(Db db, ServiceClient client, ConfigClient config, Notifier notifier, AiClient ai, ObjectMapper mapper) {
        this.db = db;
        this.client = client;
        this.config = config;
        this.notifier = notifier;
        this.ai = ai;
        this.mapper = mapper;
    }

    record Person(UUID id, String name, String title, String kind, boolean orgWide, Set<String> props, String persona) {
    }

    private static String kindOf(JsonNode u) {
        String t = u.path("party_type").asText("");
        return t.isBlank() || "null".equals(t) ? "STAFF" : t;
    }

    private Set<String> split(JsonNode n) {
        Set<String> out = new HashSet<>();
        if (n == null || n.isNull()) return out;
        for (String p : n.asText("").split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    /** Everyone in the company with the properties they work on or belong to. */
    private List<Person> directory() {
        List<Person> out = new ArrayList<>();
        Map<String, Set<String>> partyProps = new HashMap<>();
        try {
            for (JsonNode p : client.get(ServiceClient.Service.LEASING, "/internal/leasing/party-directory")) partyProps.put(p.path("party_id").asText(), split(p.path("property_ids")));
        } catch (Exception ignored) {
        }
        try {
            for (JsonNode u : client.get(ServiceClient.Service.IDENTITY, "/internal/directory")) {
                String kind = kindOf(u);
                Set<String> props = new HashSet<>();
                boolean orgWide = false;
                if ("STAFF".equals(kind)) {
                    try {
                        for (JsonNode r : mapper.readTree(u.path("roles").asText("[]"))) if ("ORG".equals(r.path("scope").asText())) orgWide = true;
                        for (JsonNode p : mapper.readTree(u.path("property_ids").asText("[]"))) props.add(p.asText());
                    } catch (Exception ignored) {
                    }
                } else if (!u.path("party_id").isNull()) {
                    props = partyProps.getOrDefault(u.path("party_id").asText(), new HashSet<>());
                }
                out.add(new Person(UUID.fromString(u.path("id").asText()), u.path("name").asText(), u.path("title").asText(""), kind, orgWide, props, u.path("persona").asText("")));
            }
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DIRECTORY_UNAVAILABLE", "The people directory isn't available right now. Try again in a moment.");
        }
        return out;
    }

    private boolean pairAllowed(String a, String b) {
        if (a.equals(b)) return "STAFF".equals(a) && config.flag("chat/allowStaffToStaff", true);
        Set<String> k = Set.of(a, b);
        if (k.equals(Set.of("STAFF", "TENANT"))) return config.flag("chat/who/tenantWithStaff", true);
        if (k.equals(Set.of("STAFF", "OWNER"))) return config.flag("chat/who/ownerWithStaff", true);
        if (k.equals(Set.of("STAFF", "VENDOR"))) return config.flag("chat/who/vendorWithStaff", true);
        if (k.equals(Set.of("TENANT", "OWNER"))) return config.flag("chat/who/tenantWithOwner", false);
        if (k.equals(Set.of("OWNER", "VENDOR"))) return config.flag("chat/who/ownerWithVendor", false);
        if (k.equals(Set.of("TENANT", "VENDOR"))) return config.flag("chat/who/tenantWithVendor", false);
        return false;
    }

    private boolean connected(Person me, Person o) {
        if (me.orgWide() || o.orgWide()) return true;
        if ("VENDOR".equals(me.kind()) || "VENDOR".equals(o.kind())) return true;
        if ("STAFF".equals(me.kind()) && "STAFF".equals(o.kind())) return true;
        return me.props().stream().anyMatch(o.props()::contains);
    }

    private Person me(List<Person> dir) {
        UUID id = UserContextHolder.require().userId();
        return dir.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> ApiException.forbidden("Your account isn't set up for messaging."));
    }

    private void requireEnabled() {
        if (!config.flag("features/MESSAGING", true)) throw ApiException.forbidden("Messaging is turned off for your company.");
    }

    // ---------- Contacts ----------
    public List<Map<String, Object>> contacts() {
        requireEnabled();
        List<Person> dir = directory();
        Person me = me(dir);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Person p : dir) {
            if (p.id().equals(me.id()) || !pairAllowed(me.kind(), p.kind()) || !connected(me, p)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id()); m.put("name", p.name()); m.put("title", p.title()); m.put("kind", p.kind());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("kind"))).thenComparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    // ---------- Conversations ----------
    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        requireEnabled();
        UUID me = UserContextHolder.require().userId();
        List<Map<String, Object>> rows = db.list("""
                select c.id, c.subject, c.context_type, c.context_id, c.context_label, c.status, c.last_message_at,
                       (select body from workflow.message m where m.conversation_id = c.id order by created_at desc limit 1) as last_body,
                       (select sender_name from workflow.message m where m.conversation_id = c.id order by created_at desc limit 1) as last_sender,
                       (select count(*) from workflow.message m where m.conversation_id = c.id and m.kind = 'USER' and m.sender_id <> ? and (cm.last_read_at is null or m.created_at > cm.last_read_at)) as unread,
                       (select string_agg(x.user_name, ', ' order by x.user_name) from workflow.conversation_member x where x.conversation_id = c.id and x.user_id <> ?) as with_names
                from workflow.conversation c join workflow.conversation_member cm on cm.conversation_id = c.id and cm.user_id = ?
                order by c.last_message_at desc limit 200""", me, me, me);
        long unread = rows.stream().mapToLong(r -> ((Number) r.get("unread")).longValue()).sum();
        return Map.of("items", rows, "unread", unread);
    }

    private Map<String, Object> member(UUID conv) {
        return db.find("select * from workflow.conversation_member where conversation_id = ? and user_id = ?", conv, UserContextHolder.require().userId())
                .orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    @Transactional
    public Map<String, Object> get(UUID id) {
        member(id);
        UUID me = UserContextHolder.require().userId();
        Map<String, Object> c = new LinkedHashMap<>(db.one("Conversation", "select * from workflow.conversation where id = ?", id));
        c.put("members", db.list("select user_id, user_name, persona from workflow.conversation_member where conversation_id = ? order by user_name", id));
        c.put("messages", db.list("select id, sender_id, sender_name, sender_persona, kind, body, created_at from workflow.message where conversation_id = ? order by created_at", id));
        db.update("update workflow.conversation_member set last_read_at = now() where conversation_id = ? and user_id = ?", id, me);
        db.update("update workflow.notification set read_at = now() where user_id = ? and link = ? and read_at is null", me, "/messages?c=" + id);
        return c;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        requireEnabled();
        UserContext ctx = UserContextHolder.require();
        String subject = str(b.get("subject")), body = str(b.get("body"));
        if (subject.isBlank()) throw ApiException.badRequest("Add a subject so everyone knows what this is about.");
        if (body.isBlank()) throw ApiException.badRequest("Write your first message.");
        String ctype = str(b.get("contextType")).isBlank() ? "GENERAL" : str(b.get("contextType")).toUpperCase();
        if (!CONTEXTS.contains(ctype)) throw ApiException.badRequest("Unknown conversation type.");
        List<?> ids = b.get("memberIds") instanceof List<?> l ? l : List.of();
        if (ids.isEmpty()) throw ApiException.badRequest("Choose who to message.");
        List<Person> dir = directory();
        Person me = me(dir);
        Map<UUID, Person> allowed = new HashMap<>();
        for (Person p : dir) if (!p.id().equals(me.id()) && pairAllowed(me.kind(), p.kind()) && connected(me, p)) allowed.put(p.id(), p);
        List<Person> members = new ArrayList<>();
        for (Object o : ids) {
            Person p = allowed.get(UUID.fromString(String.valueOf(o)));
            if (p == null) throw ApiException.forbidden("You can't message one of the people you chose. Your company's messaging rules don't allow it.");
            members.add(p);
        }
        checkBody(me, body);
        UUID id = UUID.randomUUID();
        db.update("insert into workflow.conversation (id, org_id, subject, context_type, context_id, context_label, created_by) values (?,?,?,?,?,?,?)",
                id, ctx.orgId(), subject.trim(), ctype, b.get("contextId") == null || str(b.get("contextId")).isBlank() ? null : UUID.fromString(str(b.get("contextId"))), nullIfBlank(str(b.get("contextLabel"))), ctx.userId());
        addMember(id, ctx.orgId(), me, true);
        for (Person p : members) addMember(id, ctx.orgId(), p, false);
        insertMessage(id, ctx, me, "USER", body);
        notifyOthers(id, subject, me.name(), body, members.stream().map(Person::id).toList());
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> post(UUID id, String body) {
        requireEnabled();
        member(id);
        UserContext ctx = UserContextHolder.require();
        Person me = me(directory());
        checkBody(me, body == null ? "" : body);
        insertMessage(id, ctx, me, "USER", body.trim());
        db.update("update workflow.conversation set status = 'OPEN' where id = ? and status = 'RESOLVED'", id);
        String subject = (String) db.one("Conversation", "select subject from workflow.conversation where id = ?", id).get("subject");
        List<UUID> others = db.list("select user_id from workflow.conversation_member where conversation_id = ? and user_id <> ?", id, ctx.userId()).stream().map(r -> (UUID) r.get("user_id")).toList();
        notifyOthers(id, subject, me.name(), body, others);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> resolve(UUID id, boolean resolved) {
        member(id);
        UserContext ctx = UserContextHolder.require();
        db.update("update workflow.conversation set status = ? where id = ?", resolved ? "RESOLVED" : "OPEN", id);
        Person me = me(directory());
        insertMessage(id, ctx, me, "SYSTEM", me.name() + (resolved ? " marked this resolved." : " reopened this conversation."));
        return Map.of("status", resolved ? "RESOLVED" : "OPEN");
    }

    // ---------- Catch-up (AI with a plain fallback) ----------
    @Transactional(readOnly = true)
    public Map<String, Object> assist(UUID id) {
        member(id);
        if (!config.flag("chat/aiAssist/summaries", true)) throw ApiException.badRequest("Conversation summaries are turned off for your company.");
        List<Map<String, Object>> msgs = db.list("select sender_name, sender_persona, body, created_at from workflow.message where conversation_id = ? and kind = 'USER' order by created_at desc limit 25", id);
        Collections.reverse(msgs);
        if (msgs.isEmpty()) throw ApiException.badRequest("There is nothing to summarise yet.");
        String subject = (String) db.one("Conversation", "select subject from workflow.conversation where id = ?", id).get("subject");
        boolean staff = "STAFF".equals(me(directory()).kind());
        List<Map<String, String>> input = new ArrayList<>();
        for (Map<String, Object> m : msgs) input.add(Map.of("from", String.valueOf(m.get("sender_name")), "text", String.valueOf(m.get("body"))));
        Optional<JsonNode> r = ai.call("thread", Map.of("subject", subject, "messages", input));
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.isPresent()) {
            out.put("summary", r.get().path("summary").asText());
            List<String> q = new ArrayList<>();
            r.get().path("openQuestions").forEach(x -> q.add(x.asText()));
            out.put("openQuestions", q);
            if (staff && config.flag("chat/aiAssist/suggestedReplies", true)) out.put("suggestedReply", r.get().path("suggestedReply").asText(null));
            out.put("source", "AI");
        } else {
            Set<String> people = new LinkedHashSet<>();
            for (Map<String, Object> m : msgs) people.add(String.valueOf(m.get("sender_name")));
            Map<String, Object> last = msgs.get(msgs.size() - 1);
            List<String> q = new ArrayList<>();
            for (int i = msgs.size() - 1; i >= 0 && q.size() < 3; i--) {
                String text = String.valueOf(msgs.get(i).get("body"));
                if (text.contains("?")) q.add(msgs.get(i).get("sender_name") + ": " + (text.length() > 140 ? text.substring(0, 137) + "..." : text));
            }
            out.put("summary", msgs.size() + " messages between " + String.join(" and ", people) + ". The last was from " + last.get("sender_name") + ".");
            out.put("openQuestions", q);
            out.put("source", "RULES");
        }
        return out;
    }

    // ---------- Helpers ----------
    private void checkBody(Person me, String body) {
        String t = body.trim();
        if (t.isEmpty()) throw ApiException.badRequest("Write a message first.");
        int max = config.number("chat/maxMessageLength", "2000").intValue();
        if (t.length() > max) throw ApiException.badRequest("Messages can be up to " + max + " characters.");
        if ("STAFF".equals(me.kind()) && config.flag("chat/guardrails/fairHousingCheck", true)) {
            String lower = t.toLowerCase();
            JsonNode phrases = config.at("chat/guardrails/flaggedPhrases");
            if (phrases.isArray()) for (JsonNode p : phrases)
                if (!p.asText().isBlank() && lower.contains(p.asText().toLowerCase()))
                    throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "FAIR_HOUSING",
                            "This wording (\"" + p.asText() + "\") may breach fair-housing rules. Describe the property or the rule that applies instead, and send again.");
        }
    }

    private void addMember(UUID conv, UUID org, Person p, boolean readNow) {
        db.update("insert into workflow.conversation_member (conversation_id, org_id, user_id, user_name, persona, last_read_at) values (?,?,?,?,?,?)",
                conv, org, p.id(), p.name(), p.kind() + (p.persona().isBlank() ? "" : ""), readNow ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private void insertMessage(UUID conv, UserContext ctx, Person me, String kind, String body) {
        db.update("insert into workflow.message (id, conversation_id, org_id, sender_id, sender_name, sender_persona, kind, body) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), conv, ctx.orgId(), ctx.userId(), me.name(), me.kind(), kind, body);
        db.update("update workflow.conversation set last_message_at = now() where id = ?", conv);
        db.update("update workflow.conversation_member set last_read_at = now() where conversation_id = ? and user_id = ?", conv, ctx.userId());
    }

    /** One alert per person per conversation per few minutes, so a quick back-and-forth doesn't flood the bell. */
    private void notifyOthers(UUID conv, String subject, String from, String body, List<UUID> users) {
        long window = System.currentTimeMillis() / 300_000;
        String preview = body.length() > 120 ? body.substring(0, 117) + "..." : body;
        for (UUID u : users)
            notifier.sendAlert("USER", u.toString(), from + " in \"" + subject + "\"", preview, "/messages?c=" + conv, "NORMAL", "MESSAGES", "msg:" + conv + ":" + u + ":" + window);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
