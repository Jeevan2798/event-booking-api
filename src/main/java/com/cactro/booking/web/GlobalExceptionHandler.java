package com.cactro.booking.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.util.Map;

/**
 * Extends ResponseEntityExceptionHandler so standard Spring MVC errors keep their real status
 * (malformed JSON -> 400, unknown route -> 404, wrong method -> 405, bad path variable -> 400),
 * and always return {"error": "..."} JSON.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String msg = status.value() == 400 ? "Bad request: " + shortMsg(ex) : status.toString();
        return ResponseEntity.status(status).headers(headers).body(Map.of("error", msg));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of("error", "Conflict: duplicate or invalid data"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
    }

    private static String shortMsg(Exception ex) {
        String m = String.valueOf(ex.getMessage());
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
