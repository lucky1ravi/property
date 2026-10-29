package com.propertyos.common.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small helpers for reading JSON request bodies with clear validation errors. */
public final class Req {
    private Req() {
    }

    /**
     * Reads an optional text value from a request body.
     *
     * @param b the request body
     * @param k the field name
     * @return the trimmed text, or null when absent or blank
     */
    public static String str(Map<String, ?> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    /**
     * Reads a text value that must be present.
     *
     * @param b the request body
     * @param k the field name
     * @return the trimmed text
     * @throws ApiException (400) when absent or blank
     */
    public static String required(Map<String, ?> b, String k) {
        String v = str(b, k);
        if (v == null) throw ApiException.badRequest(label(k) + " is required");
        return v;
    }

    /**
     * Reads an optional amount.
     *
     * @param b the request body
     * @param k the field name
     * @return the amount, or null when absent
     * @throws ApiException (400) when the value is not a number
     */
    public static BigDecimal money(Map<String, ?> b, String k) {
        String v = str(b, k);
        if (v == null) return null;
        try {
            BigDecimal d = new BigDecimal(v.replace("$", "").replace(",", ""));
            if (d.signum() < 0) throw ApiException.badRequest(label(k) + " can't be negative");
            return d.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label(k) + " must be a number");
        }
    }

    /**
     * Reads an amount that must be present.
     *
     * @param b the request body
     * @param k the field name
     * @return the amount
     * @throws ApiException (400) when absent or not a number
     */
    public static BigDecimal requiredMoney(Map<String, ?> b, String k) {
        BigDecimal v = money(b, k);
        if (v == null) throw ApiException.badRequest(label(k) + " is required");
        return v;
    }

    /**
     * Reads an optional whole number.
     *
     * @param b the request body
     * @param k the field name
     * @return the number, or null when absent
     * @throws ApiException (400) when the value is not a whole number
     */
    public static Integer integer(Map<String, ?> b, String k) {
        String v = str(b, k);
        if (v == null) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label(k) + " must be a whole number");
        }
    }

    /**
     * Reads an optional identifier.
     *
     * @param b the request body
     * @param k the field name
     * @return the identifier, or null when absent
     * @throws ApiException (400) when the value is not a valid identifier
     */
    public static UUID uuid(Map<String, ?> b, String k) {
        String v = str(b, k);
        if (v == null) return null;
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(label(k) + " is not a valid id");
        }
    }

    /**
     * Reads an identifier that must be present.
     *
     * @param b the request body
     * @param k the field name
     * @return the identifier
     * @throws ApiException (400) when absent or invalid
     */
    public static UUID requiredUuid(Map<String, ?> b, String k) {
        UUID v = uuid(b, k);
        if (v == null) throw ApiException.badRequest(label(k) + " is required");
        return v;
    }

    /**
     * Reads an optional ISO-8601 date ({@code yyyy-MM-dd}).
     *
     * @param b the request body
     * @param k the field name
     * @return the date, or null when absent
     * @throws ApiException (400) when the value is not a valid date
     */
    public static LocalDate date(Map<String, ?> b, String k) {
        String v = str(b, k);
        if (v == null) return null;
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest(label(k) + " must be a date (YYYY-MM-DD)");
        }
    }

    /**
     * Reads a true/false value; anything other than "true" is false.
     *
     * @param b the request body
     * @param k the field name
     * @return the flag
     */
    public static boolean bool(Map<String, ?> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    /**
     * Reads a list of text values.
     *
     * @param b the request body
     * @param k the field name
     * @return the values, or an empty list when absent
     */
    public static List<String> strings(Map<String, ?> b, String k) {
        Object v = b == null ? null : b.get(k);
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return List.of();
    }

    /**
     * Reads a required email address and checks its shape.
     *
     * @param b the request body
     * @param k the field name
     * @return the address in lower case
     * @throws ApiException (400) when absent or not a valid address
     */
    public static String email(Map<String, ?> b, String k) {
        String v = required(b, k).toLowerCase();
        if (!v.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("Enter a valid email address");
        return v;
    }

    private static String label(String k) {
        String s = k.replaceAll("([A-Z])", " $1").toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
