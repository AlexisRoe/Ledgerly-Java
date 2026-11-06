package com.ledgerly.shared.error;

import org.springframework.http.HttpStatus;

/** Every problem the API can return: a stable machine code, a type URI, a title and a status. */
public enum ErrorCatalogue {
    VALIDATION_FAILED("validation-failed", "Request validation failed", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("malformed-request", "Malformed request", HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED("unauthenticated", "Authentication required", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("forbidden", "Action not allowed for your role", HttpStatus.FORBIDDEN),
    NOT_FOUND("not-found", "Resource not found", HttpStatus.NOT_FOUND),
    EXPENSE_INVALID_TRANSITION("invalid-transition", "Illegal workflow transition", HttpStatus.CONFLICT),
    EXPENSE_SELF_DEALING("self-dealing", "Self-dealing is not allowed", HttpStatus.CONFLICT),
    LAST_OWNER("last-owner", "A team must keep at least one owner", HttpStatus.CONFLICT),
    ALREADY_MEMBER("already-member", "Already a teammate", HttpStatus.CONFLICT),
    INVITATION_NOT_PENDING("invitation-not-pending", "Invitation is no longer pending", HttpStatus.CONFLICT),
    INVITATION_EXPIRED("invitation-expired", "Invitation has expired", HttpStatus.CONFLICT),
    EMAIL_NOT_VERIFIED("email-not-verified", "Verify your email address first", HttpStatus.FORBIDDEN),
    EXPENSE_NOT_DRAFT("not-draft", "Only a draft allows this", HttpStatus.CONFLICT),
    IDEMPOTENCY_IN_PROGRESS("idempotency-in-progress", "Request already in progress", HttpStatus.CONFLICT),
    PRECONDITION_FAILED("precondition-failed", "ETag does not match", HttpStatus.PRECONDITION_FAILED),
    PRECONDITION_REQUIRED("precondition-required", "If-Match header required", HttpStatus.PRECONDITION_REQUIRED),
    EXPENSE_RULE_VIOLATED("rule-violated", "Business rule violated", HttpStatus.UNPROCESSABLE_CONTENT),
    IDEMPOTENCY_KEY_REUSED(
            "idempotency-key-reused", "Idempotency key reused with a different body", HttpStatus.UNPROCESSABLE_CONTENT),
    INTERNAL_ERROR("internal-error", "Unexpected error", HttpStatus.INTERNAL_SERVER_ERROR),
    REQUEST_REJECTED("request-rejected", "Request rejected", HttpStatus.BAD_REQUEST);

    private static final String BASE = "https://ledgerly.dev/problems/";

    private final String slug;
    private final String title;
    private final HttpStatus status;

    ErrorCatalogue(String slug, String title, HttpStatus status) {
        this.slug = slug;
        this.title = title;
        this.status = status;
    }

    public String code() {
        return name();
    }

    public String type() {
        return BASE + slug;
    }

    public String title() {
        return title;
    }

    public HttpStatus status() {
        return status;
    }
}
