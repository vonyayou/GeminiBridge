package me.vonyayou.geminibridge.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    private final me.vonyayou.geminibridge.history.HistoryService history;
    public Errors(me.vonyayou.geminibridge.history.HistoryService history) { this.history = history; }
    public static Map<String, Object> body(ApiException e) {
        var error = new LinkedHashMap<String, Object>();
        error.put("message", e.getMessage());
        error.put("type", e.status() == 401 ? "authentication_error" : e.status() >= 500 ? "server_error" : "invalid_request_error");
        error.put("param", e.param());
        error.put("code", e.code());
        return Map.of("error", error);
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status()).body(body(e)); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        history.rejected(request, 400, "The request contains invalid or missing fields.");
        return api(new ApiException(400, "invalid_request", "The request contains invalid or missing fields."));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missing() { return api(new ApiException(404, "not_found", "The resource does not exist.")); }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method() { return api(new ApiException(405, "method_not_allowed", "This HTTP method is not supported.")); }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> contentType(org.springframework.web.HttpMediaTypeNotSupportedException e, jakarta.servlet.http.HttpServletRequest request) {
        history.rejected(request, 415, "Use application/json for request bodies.");
        return api(new ApiException(415, "unsupported_media_type", "Use application/json for request bodies."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception e) {
        org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed: {}", e.getClass().getSimpleName());
        return api(new ApiException(500, "internal_error", "The request could not be completed."));
    }
}
