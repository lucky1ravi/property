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

    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        pd.setTitle(e.getStatus().getReasonPhrase());
        pd.setProperty("code", e.getCode());
        return pd;
    }

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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This change conflicts with existing data or access rules.");
        pd.setProperty("code", "CONFLICT");
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noResource(NoResourceFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. The error has been logged.");
        pd.setProperty("code", "INTERNAL");
        return pd;
    }
}
