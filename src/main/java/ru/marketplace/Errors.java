package ru.marketplace;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import ru.marketplace.model.ApiError;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    static ApiError body(String code, String message, Map<String, Object> details) {
        return new ApiError().errorCode(ApiError.ErrorCodeEnum.fromValue(code)).message(message).details(details);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException e) {
        return ResponseEntity.status(e.status).body(body(e.code, e.getMessage(), e.details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList();
        return bad(fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraints(ConstraintViolationException e) {
        return bad(e.getConstraintViolations().stream().map(v ->
                Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage())).toList());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpMessageNotReadableException e) {
        String field = "body";
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonMappingException j && !j.getPath().isEmpty()) {
                field = j.getPath().stream().map(p -> p.getFieldName() != null ? p.getFieldName() : "[" + p.getIndex() + "]")
                        .collect(java.util.stream.Collectors.joining("."));
            }
        }
        return bad(List.of(Map.of("field", field, "message", "Missing or malformed JSON, unknown field, or invalid value type")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> type(MethodArgumentTypeMismatchException e) {
        return bad(List.of(Map.of("field", e.getName(), "message", "Invalid parameter value")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(body("RESOURCE_NOT_FOUND", "Endpoint not found", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(body("METHOD_NOT_ALLOWED", "Method not allowed", null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(body("UNSUPPORTED_MEDIA_TYPE", "Use application/json", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        LoggerFactory.getLogger(Errors.class).error("Unhandled request failure", e);
        return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "Internal server error", null));
    }

    private ResponseEntity<ApiError> bad(List<Map<String, String>> fields) {
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", "Invalid request", Map.of("fields", fields)));
    }
}
