package com.example.demo.inventory;

import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> expected(ApiException error) {
        return response(error.status(), error.getMessage());
    }

    @ExceptionHandler(MongoException.class)
    ResponseEntity<?> mongo(MongoException error) {
        if (error.getCode() == 11000) return response(409, "A unique value is already in use. Refresh and try again.");
        return response(503, "Database operation could not complete. Retry stock requests with the same Idempotency-Key.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> data(DataAccessException error) {
        return response(503, "Database service is unavailable.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error) {
        return response(400, "The request contains missing, invalid, or unsupported fields.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> fallback(Exception error) {
        if (error instanceof ErrorResponse frameworkError) {
            int status = frameworkError.getStatusCode().value();
            String message = switch (status) {
                case 400 -> "The request contains missing or invalid fields.";
                case 404 -> "The requested resource was not found.";
                case 405 -> "This HTTP method is not supported.";
                default -> "The request could not be completed.";
            };
            return response(status, message);
        }
        return response(500, "An unexpected server error occurred.");
    }

    private ResponseEntity<?> response(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status, "message", message));
    }
}
