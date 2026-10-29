package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small formatting and JSON-reading helpers shared by the AI tools. */
final class StudioText {
    /**
     * Shared JSON mapper for parsing model replies.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StudioText() { }

    /** The node itself when it is an array, otherwise an empty array, so loops never meet null. */
    static JsonNode arr(JsonNode n) {
        return n == null || !n.isArray() ? MAPPER.createArrayNode() : n;
    }

    /** A number field, or 0 when missing. */
    static double d(JsonNode n, String field) {
        return n.path(field).asDouble(0);
    }

    /** 1234.5 becomes "$1,234.50". */
    static String usd(double v) {
        return String.format("$%,.2f", v);
    }

    /** A money amount rounded to cents as plain text, for comparing amounts without floating-point noise. */
    static String norm(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** At most {@code n} characters; null becomes an empty string. */
    static String trim(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }

    /** Cuts a model reply down to its outermost JSON object, dropping any prose or code fences around it. */
    static String strip(String s) {
        String t = s == null ? "" : s.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        return a >= 0 && b > a ? t.substring(a, b + 1) : t;
    }
}
