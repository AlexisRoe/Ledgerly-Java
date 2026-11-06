package com.ledgerly.shared.error;

/** One invalid field: its name in the API and what is wrong. */
public record FieldViolation(String field, String message) {}
