package com.fredvested.web.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Every error response carries a `code` from the fixed failure vocabulary the
 * frontend maps to analytics reasons (see frontend/assets/waitlist.js), and a
 * generic `message` for the visitor. Codes are for us; visitors never see them.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    public static Map<String, String> error(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    // The only Bean Validation constraints are on the email field.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(error("invalid_email", "Please enter a valid email address."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        log.warn("Malformed waitlist request body: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.badRequest().body(error("server_error", "Something went wrong. Please try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("server_error", "Something went wrong. Please try again."));
    }
}
