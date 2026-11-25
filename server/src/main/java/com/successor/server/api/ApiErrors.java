package com.successor.server.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Every error leaves the API as an RFC 9457 problem document ({@code {"status":400,"detail":"…"}}), so clients get a
 * readable reason instead of a stack trace or, worse, a silently wrong result.
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    /** The request is valid but the data cannot answer it (e.g. a player with no season of enough minutes). */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unprocessable(IllegalStateException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Cannot be computed from the data", e.getMessage());
    }

    /** Malformed JSON or a value of the wrong type. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Unreadable request body",
                "the request body is not valid JSON of the expected shape");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        p.setTitle(title);
        return p;
    }
}
