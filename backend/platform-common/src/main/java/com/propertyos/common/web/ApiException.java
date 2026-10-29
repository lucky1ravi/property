package com.propertyos.common.web;

import org.springframework.http.HttpStatus;

/** A business error with an HTTP status, returned to clients as RFC 7807 problem details. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * Returns the HTTP status this error maps to.
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Returns the stable machine-readable error code, for example {@code NOT_FOUND}.
     */
    public String getCode() {
        return code;
    }

    /**
     * Creates a 404 error.
     *
     * @param what the thing that was not found, for example "Lease"; the message reads "Lease not found"
     */
    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    /**
     * Creates a 400 error for input the caller can correct.
     *
     * @param message what is wrong and how to fix it
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /**
     * Creates a 409 error for a request that clashes with the current state.
     *
     * @param message why the request cannot be done now
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * Creates a 403 error for a signed-in user who is not allowed to do this.
     *
     * @param message what is not allowed
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    /**
     * Creates a 401 error for a caller who is not signed in.
     *
     * @param message what to do, for example sign in again
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
