package com.propertyos.common.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** One correlation id per request: kept if safe, replaced if not, visible to the code that handles the request, and gone afterwards. */
class CorrelationFilterTest {
    private final CorrelationFilter filter = new CorrelationFilter("test-service");

    private MockHttpServletResponse run(String supplied, String uri, AtomicReference<String> seen) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (supplied != null) req.addHeader(CorrelationFilter.HEADER, supplied);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest rq, jakarta.servlet.http.HttpServletResponse rs) {
                seen.set(CorrelationFilter.current());
                assertThat(MDC.get("service")).isEqualTo("test-service");
            }
        }));
        return res;
    }

    @Test
    void keepsASafeIdFromTheCaller() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse res = run("trace-12345678", "/api/things", seen);
        assertThat(seen.get()).isEqualTo("trace-12345678");
        assertThat(res.getHeader(CorrelationFilter.HEADER)).isEqualTo("trace-12345678");
        assertThat(CorrelationFilter.current()).isNull();
    }

    @Test
    void replacesMissingOrUnsafeIds() throws Exception {
        for (String bad : new String[]{null, "", "short", "has spaces and\nnewline", "x".repeat(100), "<script>alert(1)</script>"}) {
            AtomicReference<String> seen = new AtomicReference<>();
            MockHttpServletResponse res = run(bad, "/actuator/health", seen);
            assertThat(seen.get()).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
            assertThat(res.getHeader(CorrelationFilter.HEADER)).isEqualTo(seen.get());
        }
    }

    @Test
    void securityHeadersAreAdded() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(req, res, new MockFilterChain());
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
        MockHttpServletResponse other = new MockHttpServletResponse();
        other.setHeader("Cache-Control", "max-age=5");
        new SecurityHeadersFilter().doFilter(req, other, new MockFilterChain());
        assertThat(other.getHeader("Cache-Control")).isEqualTo("max-age=5");
        MockHttpServletResponse health = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/actuator/health"), health, new MockFilterChain());
        assertThat(health.getHeader("Cache-Control")).isNull();
    }
}
