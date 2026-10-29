package com.propertyos.document.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suggests where a file belongs from its name, so people don't have to fill in a category for every upload.
 * The rules are plain keyword matches, which keeps every suggestion explainable ("the file name mentions w-9");
 * the person always confirms the suggestion.
 */
@Component
public class DocumentClassifier {
    /** One filing rule: any of the pipe-separated keywords in a file name suggests this category and document type. */
    private record Rule(String keywords, String category, String docType) { }

    /** Order matters: the first rule that matches wins, so specific names (1099-NEC) come before general ones (1099). */
    private static final List<Rule> RULES = List.of(
            new Rule("w-9|w9|form w9", "TAX", "W-9"),
            new Rule("1099-nec", "TAX", "1099-NEC"),
            new Rule("1099", "TAX", "1099-MISC"),
            new Rule("sales tax", "TAX", "Sales tax return"),
            new Rule("acord|coi|certificate of insurance|cert of ins", "INSURANCE", "Certificate of insurance"),
            new Rule("renter", "INSURANCE", "Renters insurance"),
            new Rule("insurance|policy|declarations", "INSURANCE", "Property insurance policy"),
            new Rule("paystub|pay stub|pay_stub|payslip|earnings statement|w-2|w2", "APPLICATION", "Proof of income"),
            new Rule("bank statement|bank_statement", "APPLICATION", "Bank statement"),
            new Rule("offer letter|offer_letter|employment letter", "APPLICATION", "Offer letter"),
            new Rule("reference", "APPLICATION", "Reference letter"),
            new Rule("screening|credit report|background", "APPLICATION", "Screening report"),
            new Rule("passport", "IDENTITY", "Passport"),
            new Rule("license|licence|driver|photo id|photo_id|state id|id card", "IDENTITY", "Photo ID"),
            new Rule("pet addendum|pet_addendum|pet", "LEASE", "Pet addendum"),
            new Rule("addendum", "LEASE", "Lease addendum"),
            new Rule("move-in|move in|movein", "LEASE", "Move-in inspection"),
            new Rule("move-out|move out|moveout", "LEASE", "Move-out inspection"),
            new Rule("renewal", "LEASE", "Renewal offer"),
            new Rule("lease", "LEASE", "Lease agreement"),
            new Rule("utility|electric|water bill|gas bill|sewer", "BILL", "Utility bill"),
            new Rule("property tax", "BILL", "Property tax bill"),
            new Rule("hoa", "BILL", "HOA bill"),
            new Rule("quote|estimate|proposal", "BILL", "Quote"),
            new Rule("receipt", "BILL", "Receipt"),
            new Rule("invoice|bill", "BILL", "Vendor invoice"),
            new Rule("owner statement|statement", "STATEMENT", "Owner statement"),
            new Rule("management agreement", "CONTRACT", "Management agreement"),
            new Rule("vendor agreement|service agreement", "CONTRACT", "Vendor agreement"),
            new Rule("agreement|contract", "CONTRACT", "Service contract"),
            new Rule("permit", "PROPERTY", "Permit"),
            new Rule("certificate of occupancy", "PROPERTY", "Certificate of occupancy"),
            new Rule("appraisal", "PROPERTY", "Appraisal"),
            new Rule("floor plan|floorplan", "PROPERTY", "Floor plan"),
            new Rule("inspection", "PROPERTY", "Inspection report"),
            new Rule("notice to vacate|vacate", "NOTICE", "Notice to vacate"),
            new Rule("entry notice|notice of entry", "NOTICE", "Entry notice"),
            new Rule("rent increase", "NOTICE", "Rent increase notice"),
            new Rule("late notice|late rent", "NOTICE", "Late rent notice"),
            new Rule("adverse action", "NOTICE", "Adverse action notice"),
            new Rule("-", "OTHER", "Other")); // placeholder that never matches a real name

    /**
     * Confidence given when the file name itself names the document type.
     */
    private static final double NAMED_CONFIDENCE = 0.85;
    /**
     * Confidence given when only the record the file is attached to hints at the type.
     */
    private static final double CONTEXT_CONFIDENCE = 0.4;

    /**
     * @param fileName   the uploaded file's name, may be null
     * @param entityType the kind of record the file is being added to (WORK_ORDER, APPLICATION, VENDOR, INVOICE...), may be null
     * @return category, docType, confidence (0 to 1) and a one-sentence reason
     */
    public Map<String, Object> classify(String fileName, String entityType) {
        String name = (fileName == null ? "" : fileName).toLowerCase().replace('_', ' ');
        for (Rule rule : RULES) {
            for (String keyword : rule.keywords().split("\\|")) {
                if (name.contains(keyword.replace('_', ' '))) {
                    return suggestion(rule.category(), rule.docType(), NAMED_CONFIDENCE, "The file name mentions “" + keyword + "”.");
                }
            }
        }
        return fromContext(name, entityType);
    }

    /** No keyword matched, so fall back on what the file is being attached to. */
    private static Map<String, Object> fromContext(String name, String entityType) {
        boolean image = name.matches(".*\\.(jpg|jpeg|png|heic|webp|gif)$");
        if (image && "WORK_ORDER".equals(entityType)) return suggestion("WORK_ORDER", "Photo", 0.7, "A photo added to a work order.");
        if ("APPLICATION".equals(entityType)) return suggestion("APPLICATION", "Proof of income", CONTEXT_CONFIDENCE, "Added to an application.");
        if ("VENDOR".equals(entityType)) return suggestion("INSURANCE", "Certificate of insurance", CONTEXT_CONFIDENCE, "Added to a vendor record.");
        if ("INVOICE".equals(entityType) || "STATEMENT".equals(entityType)) return suggestion("BILL", "Receipt", CONTEXT_CONFIDENCE, "Added to a billing record.");
        return suggestion(image ? "PROPERTY" : "OTHER", image ? "Inspection report" : "Other", 0.2, "No strong clue in the file name.");
    }

    private static Map<String, Object> suggestion(String category, String docType, double confidence, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("docType", docType);
        m.put("confidence", confidence);
        m.put("reason", reason);
        return m;
    }
}
