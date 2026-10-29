package com.propertyos.common.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class WordsTest {
    @Test
    @DisplayName("method names that start with a verb become sentences")
    void verbNames() {
        assertThat(Words.summary("approveLease", HttpMethod.POST)).isEqualTo("Approve lease");
        assertThat(Words.summary("createProperty", HttpMethod.POST)).isEqualTo("Create property");
    }

    @Test
    @DisplayName("bare nouns take their verb from the HTTP method")
    void nounNames() {
        assertThat(Words.summary("properties", HttpMethod.GET)).isEqualTo("Get properties");
        assertThat(Words.summary("marketRent", HttpMethod.PUT)).isEqualTo("Update market rent");
        assertThat(Words.summary("renewal", HttpMethod.POST)).isEqualTo("Create renewal");
        assertThat(Words.summary("document", HttpMethod.DELETE)).isEqualTo("Delete document");
    }

    @Test
    @DisplayName("operations are grouped by the resource in their path")
    void tags() {
        assertThat(Words.resourceTag("/api/leasing/leases/{id}/renewal")).isEqualTo("Leases");
        assertThat(Words.resourceTag("/api/leasing/properties")).isEqualTo("Properties");
        assertThat(Words.resourceTag("/api/leasing/public/listings/{id}")).isEqualTo("Public listings");
        assertThat(Words.resourceTag("/api/ai/status")).isEqualTo("Status");
    }
}
