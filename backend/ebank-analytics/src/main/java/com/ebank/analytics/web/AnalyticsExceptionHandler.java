package com.ebank.analytics.web;

import com.ebank.analytics.application.AnalyticsUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Runs before the global handler, which would turn this into a 500. */
@RestControllerAdvice(assignableTypes = AnalyticsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AnalyticsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsExceptionHandler.class);

    @ExceptionHandler(AnalyticsUnavailableException.class)
    ProblemDetail unavailable(AnalyticsUnavailableException ex) {
        log.warn("Analytics query failed: {}", ex.getCause() == null ? "?" : ex.getCause().getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Analytics are temporarily unavailable. Try again shortly.");
        problem.setProperty("code", "ANALYTICS_UNAVAILABLE");
        return problem;
    }
}
