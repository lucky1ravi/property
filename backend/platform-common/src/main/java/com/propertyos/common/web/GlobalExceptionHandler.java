package com.propertyos.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every error from every service has the same shape, so the screen can always treat it the same way:
 * {@code status}, {@code title}, {@code detail} (a sentence written for the person: what went wrong and what to do),
 * {@code code} (stable, for programs), {@code correlationId} (quote it to support to find the exact request in the logs),
 * {@code timestamp}, and for validation problems {@code errors} (one entry per field).
 * Internal details are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Builds an error body in the standard shape. */
    static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title != null ? title : status.getReasonPhrase());
        pd.setProperty("code", code);
        String id = MDC.get("correlationId");
        if (id != null) pd.setProperty("correlationId", id);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    /**
     * Maps a deliberate {@link ApiException} to its status with a readable message and error code.
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException e) {
        return problem(e.getStatus(), null, e.getMessage(), e.getCode());
    }

    /**
     * Maps bean-validation failures to a 400 that names each field and what is wrong with it, in plain words.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(f -> {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("field", f.getField());
            one.put("label", Req.label(f.getField()));
            one.put("message", humanize(f.getDefaultMessage()));
            errors.add(one);
        });
        String detail = errors.isEmpty() ? "Check what you entered and try again."
                : errors.size() == 1 ? errors.get(0).get("label") + " " + errors.get(0).get("message") + "."
                : "Please fix these and try again: " + String.join("; ", errors.stream().map(x -> x.get("label") + " " + x.get("message")).toList()) + ".";
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Please check what you entered", detail, "VALIDATION");
        pd.setProperty("errors", errors);
        return pd;
    }

    /** Turns a validation library's wording into something a person would say. */
    private static String humanize(String m) {
        if (m == null) return "isn't valid";
        String s = m.trim();
        if (s.equals("must not be blank") || s.equals("must not be null") || s.equals("must not be empty")) return "is required";
        if (s.equals("must be a well-formed email address")) return "must be a valid email address";
        var size = java.util.regex.Pattern.compile("size must be between (\\d+) and (\\d+)").matcher(s);
        if (size.matches()) return "must be between " + size.group(1) + " and " + size.group(2) + " characters";
        return s.startsWith("must ") || s.startsWith("is ") ? s : "isn't valid";
    }

    /**
     * Maps database constraint violations (including row-level-security rejections) to a 409 without leaking SQL details.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "This can't be saved", "This change conflicts with information that already exists, or with who is allowed to see it. Refresh the page and check, then try again.", "CONFLICT");
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    /**
     * Maps malformed or unreadable requests (bad JSON, wrong types, missing parameters) to a 400.
     */
    public ProblemDetail badInput(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Please check what you entered", "Something you entered wasn't in the form we expected. Check it and try again.", "BAD_REQUEST");
    }

    /**
     * Maps a call with an unsupported HTTP verb to a 405.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ProblemDetail wrongMethod(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Not available", "This action isn't available here (" + e.getMethod() + "). If you were using the product normally, contact support with the reference below.", "METHOD_NOT_ALLOWED");
    }

    /** A file bigger than the server accepts. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "That file is too large", "That file is bigger than we accept. Choose a smaller file, or compress it and try again.", "PAYLOAD_TOO_LARGE");
    }

    /** A kind of data the endpoint does not read. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ProblemDetail unsupportedType(org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported format", "That kind of file or data can't be used here. Choose a different file and try again.", "UNSUPPORTED_MEDIA_TYPE");
    }

    /**
     * Maps an unknown URL to a 404. The code tells programs this is a missing route rather than a missing record.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noResource(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "We couldn't find what you asked for. If you followed a link, it may be out of date.", "ROUTE_NOT_FOUND");
    }

    /**
     * Last resort: logs the error and returns a generic 500 that exposes no internals. The reference lets support find the exact failure.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", "Something went wrong on our side and your request wasn't completed. Please try again. If it keeps happening, contact support and quote the reference below.", "INTERNAL");
    }
}
