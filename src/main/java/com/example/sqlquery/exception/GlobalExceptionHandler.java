package com.example.sqlquery.exception;

import com.example.sqlquery.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常 → 统一响应体的翻译层。
 *
 * <h2>为什么必须一条条列出来，而不是只留一个兜底</h2>
 *
 * <p>兜底的 {@code @ExceptionHandler(Exception.class)} 会把<b>所有</b>没被单独处理的异常
 * 都翻译成「系统异常」。对使用者来说，「路径变量类型不对」和「数据库炸了」
 * 得到的是同一句话 —— 既看不出是自己的问题，也无法自助修正，
 * 只能来找后端。所以<b>每一种可预见的客户端错误都要有自己的分支</b>。
 *
 * <p>曾经漏掉的例子：{@code GET /api/export/abc/download} 里的 {@code abc} 无法转成
 * {@link Long}，抛的是 {@link MethodArgumentTypeMismatchException}，
 * 当时没有对应分支 → 用户看到「系统异常」。
 *
 * <p>日志级别也分了层：客户端错误（4xx 那一类）打 {@code warn} 不打堆栈，
 * 服务端错误才打 {@code error} + 堆栈 —— 否则真正的故障会被大量噪音淹掉。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = "参数错误";
        if (e.getBindingResult().getFieldError() != null) {
            message = e.getBindingResult().getFieldError().getDefaultMessage();
        }
        return Result.error(message);
    }

    /**
     * 方法级参数校验失败（{@code @RequestParam} / {@code @PathVariable} 上的
     * {@code @Min}、{@code @Max} 等，配合类上的 {@code @Validated}）。
     *
     * <p>注意：这类校验抛的是 {@link ConstraintViolationException}，
     * 和 {@code @RequestBody} 上 {@code @Valid} 失败抛的
     * {@link MethodArgumentNotValidException} 是<b>两个不同的异常</b>，必须分别处理。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("参数错误");
        return Result.error(message);
    }

    /**
     * 方法参数校验失败的另一种抛出形式。
     *
     * <p>Spring 6.1 起，把约束注解直接写在控制器方法参数上（不经过类级 {@code @Validated}）
     * 时抛的是这个；它和 {@link ConstraintViolationException} 是并列关系，不是父子关系，
     * 少处理一个就会掉进兜底分支。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public Result<Void> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        return Result.error("参数校验失败");
    }

    /**
     * 路径变量 / 请求参数类型不匹配，例如 {@code /api/export/abc/download} 里的 {@code abc}
     * 转不成 {@code Long}。
     *
     * <p>这是最容易漏的一条：它既不是校验失败（值本身合法，只是类型不对），
     * 也不是业务异常，不单独处理就一定是「系统异常」。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = e.getRequiredType() == null ? "合法值" : e.getRequiredType().getSimpleName();
        log.warn("参数类型不匹配：name={}, value={}", e.getName(), e.getValue());
        return Result.error("参数格式错误：" + e.getName() + " 应为 " + expected);
    }

    /** 请求体不是合法 JSON，或字段类型对不上（例如把数字字段写成字符串）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.error("请求体格式错误，请检查 JSON 是否合法、字段类型是否正确");
    }

    /** 缺少必填的请求参数，例如 {@code PUT /api/datasource/1/status} 没带 {@code status}。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error("缺少请求参数：" + e.getParameterName());
    }

    /** 请求方法不对，例如用 POST 调一个只接受 GET 的接口。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.error("请求方法不支持：" + e.getMethod());
    }

    /** Content-Type 不对，例如没带 {@code application/json} 就发 JSON body。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return Result.error("请求内容类型不支持：" + e.getContentType());
    }

    /**
     * 接口不存在。
     *
     * <p>不处理的话它会掉进兜底分支返回 HTTP 200 + 「系统异常」，
     * 把「你路径写错了」伪装成「服务端炸了」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.error("接口不存在：" + e.getResourcePath());
    }

    /**
     * 唯一约束冲突。
     *
     * <p>这里<b>不能写死「用户名已存在」</b>：{@code DuplicateKeyException} 是所有唯一索引
     * 冲突的父类型，数据源重名、以后任何新加的唯一约束都会走到这里，
     * 写死一个具体提示等于对另一部分场景给出错误信息。
     *
     * <p>业务代码里能提前判断的场景（注册重名、数据源重名）都已经在 Service 层
     * 翻译成了准确的 BusinessException，这里只是并发下绕过应用层预检时的兜底，
     * 所以按约束名做一次映射，认不出来就给通用提示。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage();
        log.warn("唯一约束冲突：{}", detail);

        if (detail.contains("uk_user_id_name")) {
            return Result.error("数据源名称已存在");
        }
        if (detail.contains("username")) {
            return Result.error("用户名已存在");
        }
        return Result.error("数据已存在，请勿重复提交");
    }

    /**
     * 兜底分支：走到这里说明是<b>没预料到</b>的异常。
     *
     * <p>对外只给一句「系统异常」——不把堆栈、SQL、类名这些内部信息暴露给调用方；
     * 对内必须打完整堆栈，否则线上问题无法定位。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统异常");
    }
}
