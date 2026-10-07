package ru.marketplace;

import java.util.Map;

public class BusinessException extends RuntimeException {
    final int status;
    final String code;
    final Map<String, Object> details;

    public BusinessException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public BusinessException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static BusinessException validation(String field, String message) {
        return new BusinessException(400, "VALIDATION_ERROR", "Invalid request",
                Map.of("fields", java.util.List.of(Map.of("field", field, "message", message))));
    }
}
