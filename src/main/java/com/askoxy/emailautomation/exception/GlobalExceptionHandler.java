package com.askoxy.emailautomation.exception;

import com.askoxy.emailautomation.dto.EmailAutomationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public EmailAutomationResponse handleException(Exception ex) {

        // Walk the cause chain to find the real root cause
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String rootMessage = root.getClass().getSimpleName() + ": " + root.getMessage();

        // Log the full stack trace — visible in console
        log.error("[GlobalExceptionHandler] Request failed. Root cause: {}", rootMessage, ex);

        return EmailAutomationResponse.builder()
                .success(false)
                .message(ex.getMessage() + " | Root cause: " + rootMessage)
                .build();
    }
}