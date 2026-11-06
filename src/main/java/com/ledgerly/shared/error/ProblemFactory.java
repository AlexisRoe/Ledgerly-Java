package com.ledgerly.shared.error;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** Builds RFC 9457 problem bodies with the Ledgerly extensions {@code code}, {@code traceId} and {@code errors}. */
@Component
public class ProblemFactory {

    private final ObjectProvider<Tracer> tracer;

    ProblemFactory(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public ProblemDetail create(ErrorCatalogue error, @Nullable String detail, List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatus(error.status());
        problem.setType(URI.create(error.type()));
        problem.setTitle(error.title());
        if (detail != null) {
            problem.setDetail(detail);
        }
        problem.setProperty("code", error.code());
        addTraceId(problem);
        if (!violations.isEmpty()) {
            problem.setProperty("errors", violations);
        }
        return problem;
    }

    public ProblemDetail create(LedgerlyException e) {
        return create(e.error(), e.getMessage(), e.violations());
    }

    /** Adds the extensions to a problem Spring built itself (404 for unknown paths, 405, 415, ...). */
    public void enrich(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        if (properties == null || !properties.containsKey("code")) {
            problem.setProperty("code", ErrorCatalogue.REQUEST_REJECTED.code());
            addTraceId(problem);
        }
    }

    private void addTraceId(ProblemDetail problem) {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span != null) {
            problem.setProperty("traceId", span.context().traceId());
        }
    }
}
