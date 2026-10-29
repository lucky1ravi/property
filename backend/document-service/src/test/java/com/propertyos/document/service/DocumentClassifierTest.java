package com.propertyos.document.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentClassifierTest {
    private final DocumentClassifier classifier = new DocumentClassifier();

    @ParameterizedTest(name = "{0} is filed as {1} / {2}")
    @CsvSource({
            "W9_Lone_Star_Plumbing.pdf, TAX, W-9",
            "1099-NEC_2026.pdf, TAX, 1099-NEC",
            "ACORD certificate.pdf, INSURANCE, Certificate of insurance",
            "renters_policy.pdf, INSURANCE, Renters insurance",
            "pay_stub_oct.pdf, APPLICATION, Proof of income",
            "driver license.jpg, IDENTITY, Photo ID",
            "pet addendum signed.pdf, LEASE, Pet addendum",
            "Lease Agreement Unit 4B.pdf, LEASE, Lease agreement",
            "water bill sept.pdf, BILL, Utility bill",
            "Owner Statement 2026-09.pdf, STATEMENT, Owner statement",
            "notice to vacate.docx, NOTICE, Notice to vacate"
    })
    void recognisesCommonDocuments(String fileName, String category, String docType) {
        Map<String, Object> s = classifier.classify(fileName, null);
        assertThat(s).containsEntry("category", category).containsEntry("docType", docType);
        assertThat((double) s.get("confidence")).isEqualTo(0.85);
    }

    @Test
    @DisplayName("specific names win over general ones")
    void specificBeforeGeneral() {
        assertThat(classifier.classify("1099-NEC.pdf", null)).containsEntry("docType", "1099-NEC");
        assertThat(classifier.classify("1099-misc.pdf", null)).containsEntry("docType", "1099-MISC");
    }

    @Test
    @DisplayName("the reason quotes the keyword that matched")
    void explainsItself() {
        assertThat(classifier.classify("w-9 form.pdf", null).get("reason").toString()).contains("w-9");
    }

    @Test
    @DisplayName("without a clue in the name, the record being added to guides a low-confidence suggestion")
    void usesContext() {
        assertThat(classifier.classify("IMG_0042.jpg", "WORK_ORDER")).containsEntry("category", "WORK_ORDER").containsEntry("docType", "Photo");
        assertThat(classifier.classify("scan.pdf", "VENDOR")).containsEntry("category", "INSURANCE");
        assertThat(classifier.classify("scan.pdf", "APPLICATION")).containsEntry("docType", "Proof of income");
        assertThat((double) classifier.classify("scan.pdf", "INVOICE").get("confidence")).isLessThan(0.5);
    }

    @Test
    @DisplayName("an unknown file with no context is filed as Other with the lowest confidence")
    void unknown() {
        Map<String, Object> s = classifier.classify("zzz.bin", null);
        assertThat(s).containsEntry("category", "OTHER");
        assertThat((double) s.get("confidence")).isEqualTo(0.2);
        assertThat(classifier.classify(null, null)).containsEntry("category", "OTHER");
    }
}
