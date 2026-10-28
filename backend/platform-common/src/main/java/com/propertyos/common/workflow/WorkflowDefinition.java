package com.propertyos.common.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/** A configurable state machine: states, allowed transitions, permissions, SLAs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
        String key,
        String name,
        String entity,
        Integer version,
        String initialState,
        List<State> states,
        List<Transition> transitions
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(String key, String label, String category, Integer slaHours) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String action, String label, List<String> from, String to, String permission,
                             boolean requiresReason, boolean system, String limitKey) {
    }

    public Optional<State> state(String key) {
        return states.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    public String label(String stateKey) {
        return state(stateKey).map(State::label).orElse(stateKey);
    }
}
