package com.axion11.visualops.exception;

/**
 * Thrown when a request's JWT principal (email) no longer resolves to a {@code User} row —
 * e.g. the account was deleted after the token was issued. Mapped to 401 so the frontend's
 * existing "session expired" prompt handles it instead of surfacing a raw 500.
 */
public class AuthenticatedUserNotFoundException extends RuntimeException {
    public AuthenticatedUserNotFoundException(String message) {
        super(message);
    }
}
