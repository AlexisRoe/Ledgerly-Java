package com.ledgerly.shared.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** The one place where exceptions become problem responses. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private final ProblemFactory problems;

    ProblemExceptionHandler(ProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(LedgerlyException.class)
    ResponseEntity<Object> ledgerly(LedgerlyException e, WebRequest request) {
        ProblemDetail problem = problems.create(e);
        if (e instanceof TooManyRequests limited) {
            return ResponseEntity.status(problem.getStatus())
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(Math.max(1, limited.retryAfter().toSeconds())))
                    .body(problem);
        }
        return respond(problem);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class
    })
    ResponseEntity<Object> lostRace(Exception e) {
        return respond(problems.create(new PreconditionFailed()));
    }

    /** Constraints on controller parameters (@Min, @Pattern, ...) arrive through method validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolations(ConstraintViolationException e) {
        List<FieldViolation> violations = e.getConstraintViolations().stream()
                .map(v -> new FieldViolation(lastSegment(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return respond(problems.create(ErrorCatalogue.VALIDATION_FAILED, "Request validation failed", violations));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(problems.create(ErrorCatalogue.INTERNAL_ERROR, null, List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::violation)
                .toList();
        return respond(problems.create(ErrorCatalogue.VALIDATION_FAILED, "Request validation failed", violations));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new FieldViolation(
                                r.getMethodParameter().getParameterName() == null
                                        ? "request"
                                        : String.valueOf(r.getMethodParameter().getParameterName()),
                                String.valueOf(err.getDefaultMessage()))))
                .toList();
        return respond(problems.create(ErrorCatalogue.VALIDATION_FAILED, "Request validation failed", violations));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // A body that grew past the cap while being read (chunked uploads have no Content-Length to check).
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLarge tooLarge) {
                return respond(problems.create(tooLarge));
            }
        }
        return respond(problems.create(
                ErrorCatalogue.MALFORMED_REQUEST,
                "The request body is missing, malformed or contains unknown fields",
                List.of()));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            if ("If-Match".equalsIgnoreCase(missing.getHeaderName())) {
                return respond(problems.create(new PreconditionRequired("If-Match")));
            }
            return respond(problems.create(
                    ErrorCatalogue.VALIDATION_FAILED,
                    "Request validation failed",
                    List.of(new FieldViolation(missing.getHeaderName(), "header is required"))));
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(problems.create(new PayloadTooLarge("The upload is larger than the limit")));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problems.enrich(problem);
        }
        return response;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private FieldViolation violation(FieldError error) {
        return new FieldViolation(error.getField(), String.valueOf(error.getDefaultMessage()));
    }

    private static ResponseEntity<Object> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
