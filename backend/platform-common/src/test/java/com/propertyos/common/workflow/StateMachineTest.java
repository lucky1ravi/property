package com.propertyos.common.workflow;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Db;
import com.propertyos.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** An action on a record that does not exist is a clean "not found", never a server error. */
class StateMachineTest {
    private final Db db = Mockito.mock(Db.class);
    private final StateMachine machine = new StateMachine(Mockito.mock(WorkflowEngine.class), db, Mockito.mock(AuditService.class));

    @Test
    void aMissingRecordIsNotFound() {
        when(db.value(eq(String.class), anyString(), any(Object[].class))).thenThrow(new EmptyResultDataAccessException(1));
        assertThatThrownBy(() -> machine.apply("Showing", "leasing.showing", UUID.randomUUID(), "SHOWING", "CONFIRM", null, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("Showing");
    }

    @Test
    void aNullStatusIsNotFound() {
        when(db.value(eq(String.class), anyString(), any(Object[].class))).thenReturn(null);
        assertThatThrownBy(() -> machine.apply("Lease", "leasing.lease", UUID.randomUUID(), "LEASE", "ACTIVATE", null, null, null))
                .isInstanceOf(ApiException.class);
    }
}
