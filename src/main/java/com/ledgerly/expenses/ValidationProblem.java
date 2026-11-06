package com.ledgerly.expenses;

/** The first rule a would-be expense breaks, in terms a client can show. */
public record ValidationProblem(String field, String message) {}
