package com.propertyos.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/** Consistent RFC 7807 error bodies across all services. Internal details are logged, never returned. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps a deliberate {@link ApiException} to its status with a readable message and error code.
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        pd.setTitle(e.getStatus().getReasonPhrase());
        pd.setProperty("code", e.getCode());
        return pd;
    }

    /**
     * Maps bean-validation failures to a 400 that lists each field problem.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation failed");
        pd.setProperty("code", "VALIDATION");
        return pd;
    }

    /**
     * Maps database constraint violations (including row-level-security rejections) to a 409 without leaking SQL details.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This change conflicts with existing data or access rules.");
        pd.setProperty("code", "CONFLICT");
        return pd;
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    /**
     * Maps malformed or unreadable requests (bad JSON, wrong types, missing parameters) to a 400.
     */
    public ProblemDetail badInput(Exception e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request wasn't in the expected format. Check what you entered and try again.");
        pd.setProperty("code", "BAD_REQUEST");
        return pd;
    }

    /**
     * Maps a call with an unsupported HTTP verb to a 405.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ProblemDetail wrongMethod(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "This endpoint doesn't support " + e.getMethod() + ".");
        pd.setProperty("code", "METHOD_NOT_ALLOWED");
        return pd;
    }

    /**
     * Maps an unknown URL to a 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noResource(NoResourceFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    /**
     * Last resort: logs the error and returns a generic 500 that exposes no internals.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. The error has been logged.");
        pd.setProperty("code", "INTERNAL");
        return pd;
    }
}
