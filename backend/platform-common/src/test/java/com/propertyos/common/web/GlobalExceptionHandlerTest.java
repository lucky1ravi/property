package com.propertyos.common.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {
    @RestController
    static class Probe {
        @GetMapping("/missing") String missing() { throw ApiException.notFound("Lease"); }
        @GetMapping("/clash") String clash() { throw ApiException.conflict("Already exists"); }
        @GetMapping("/boom") String boom() { throw new IllegalStateException("secret internal detail"); }
        @PostMapping("/echo") String echo(@RequestBody java.util.Map<String, Object> body) { return "ok"; }
        @GetMapping("/number/{n}") String number(@PathVariable int n) { return "ok"; }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("a business error becomes problem details with its status and code")
    void apiException() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.detail").value("Lease not found"));
        mvc.perform(get("/clash")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("unexpected failures never leak internal details")
    void unexpected() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value("Something went wrong. The error has been logged."));
    }

    @Test
    @DisplayName("malformed JSON and bad parameters are 400, not 500")
    void badInput() throws Exception {
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/number/abc")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("calling an endpoint with the wrong HTTP method is 405")
    void wrongMethod() throws Exception {
        mvc.perform(post("/missing")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
