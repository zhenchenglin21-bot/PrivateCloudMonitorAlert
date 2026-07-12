package com.privatecloud.config;

import com.privatecloud.dto.ResultResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResultResponse<?> handleException(Exception e) {
        return ResultResponse.fail(e.getMessage());
    }
}
