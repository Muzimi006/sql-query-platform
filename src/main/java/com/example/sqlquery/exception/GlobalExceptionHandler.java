package com.example.sqlquery.exception;

import com.example.sqlquery.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e){
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e){
        String message = "参数错误";
        if (e.getBindingResult().getFieldError() != null) {
            message = e.getBindingResult().getFieldError().getDefaultMessage();
        }
        return Result.error(message);
    }

    /**
     * 方法级参数校验失败（@RequestParam / @PathVariable 上的 @Min、@Max 等）。
     *
     * <p>注意：这类校验抛的是 {@link ConstraintViolationException}，
     * 和 @RequestBody 上 @Valid 失败抛的 {@link MethodArgumentNotValidException}
     * 是<b>两个不同的异常</b>，必须分别处理，否则会落到兜底分支返回"系统异常"。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("参数错误");
        return Result.error(message);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        return Result.error("用户名已存在");
    }


    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统异常");
    }
}
