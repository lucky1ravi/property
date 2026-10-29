package com.propertyos.identity.web;

import com.propertyos.common.demo.DemoData;
import com.propertyos.common.web.ApiException;
import com.propertyos.identity.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Public sign-in endpoints. Rate limited at the gateway. */
@RestController
@RequestMapping("/api/identity/public")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Persona picker for the demo: one click signs in as each role. */
    @GetMapping("/demo-users")
    public Map<String, Object> demoUsers() {
        if (!auth.demoMode()) return Map.of("enabled", false, "users", List.of());
        List<Map<String, Object>> users = new ArrayList<>();
        for (DemoData.User u : DemoData.users()) {
            String org = DemoData.orgs().stream().filter(o -> o.id().equals(u.orgId())).findFirst().map(DemoData.Org::name).orElse("");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id());
            m.put("name", u.name());
            m.put("email", u.email());
            m.put("title", u.title());
            m.put("persona", u.persona());
            m.put("roleKey", u.roleKey());
            m.put("organization", org);
            users.add(m);
        }
        return Map.of("enabled", true, "users", users);
    }

    /**
     * Signs in as a demo persona without a password. Only works when demo mode is on.
     */
    @PostMapping("/demo-login")
    public Map<String, Object> demoLogin(@RequestBody Map<String, String> body) {
        String id = body.get("userId");
        if (id == null) throw ApiException.badRequest("userId is required");
        return auth.demoLogin(UUID.fromString(id));
    }

    /**
     * Signs in with email and password.
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return auth.login(body.get("email"), body.get("password"));
    }

    /** Tells the sign-in page whether to show demo hints. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demo", auth.demoMode());
        if (auth.demoMode()) m.put("demoPassword", auth.demoPassword());
        return m;
    }

    /**
     * Sends a one-time sign-in code to an email address.
     */
    @PostMapping("/otp/request")
    public Map<String, Object> requestOtp(@RequestBody Map<String, String> body) {
        return auth.requestOtp(body.get("email"));
    }

    /**
     * Checks a one-time code and signs the user in.
     */
    @PostMapping("/otp/verify")
    public Map<String, Object> verifyOtp(@RequestBody Map<String, String> body) {
        return auth.verifyOtp(body.get("email"), body.get("code"));
    }
}
