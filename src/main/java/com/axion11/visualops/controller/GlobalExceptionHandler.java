package com.axion11.visualops.controller;

import com.axion11.visualops.exception.AuthenticatedUserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Services throw {@link NoSuchElementException} for missing entities (batches, uploads, projects,
     * teams, etc.). Map it to 404 so callers can distinguish "doesn't exist" from "broken" — without
     * this, every poll for a deleted batch becomes a 500 and a frontend error-monitor alert.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /**
     * The JWT's principal email no longer matches a User row (e.g. the account was deleted after
     * the token was issued). 401 lets the frontend's session-expired prompt handle it instead of
     * surfacing a raw 500.
     */
    @ExceptionHandler(AuthenticatedUserNotFoundException.class)
    public ResponseEntity<String> handleAuthenticatedUserNotFound(AuthenticatedUserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    /** Validation failures (e.g. moving a batch into its own sub-batch) — 400, not a server error. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
}
