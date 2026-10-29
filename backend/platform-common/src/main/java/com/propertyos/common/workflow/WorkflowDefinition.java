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
    /**
     * One step in a workflow.
     *
     * @param key      stable identifier stored on the record
     * @param label    text shown to people
     * @param category grouping such as open, active or closed
     * @param slaHours hours allowed in this state before it counts as late, or null for no limit
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(String key, String label, String category, Integer slaHours) {
    }

    /**
     * A move between states.
     *
     * @param action         identifier used in the API
     * @param label          button text shown to people
     * @param from           states the move is allowed from
     * @param to             state after the move
     * @param permission     permission the user needs, for example {@code LEASE:APPROVE}
     * @param requiresReason whether the caller must give a reason
     * @param system         true when only the system, not a person, may perform it
     * @param limitKey       name of the user's approval limit that applies, or null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String action, String label, List<String> from, String to, String permission,
                             boolean requiresReason, boolean system, String limitKey) {
    }

    /**
     * Finds a state by key.
     */
    public Optional<State> state(String key) {
        return states.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    /**
     * Returns the display label for a state, or the key itself when unknown.
     */
    public String label(String stateKey) {
        return state(stateKey).map(State::label).orElse(stateKey);
    }
}
