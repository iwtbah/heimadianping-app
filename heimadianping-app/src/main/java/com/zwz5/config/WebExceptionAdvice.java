package com.zwz5.config;

import com.zwz5.common.Result;
import com.zwz5.exception.NullException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }

    @ExceptionHandler(NullException.class)
    public Result NullException(NullException e) {
        log.error(e.getMessage(), e);
        return Result.fail(e.getMessage());
    }
}
