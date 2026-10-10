package com.example.sqlquery.exception;

/**
 * 业务异常：由调用方的输入或环境导致、且<b>可以给用户一句人话</b>的失败。
 *
 * <h2>⚠️ 为什么必须有带 cause 的构造器</h2>
 *
 * <p>这个类一开始<b>只有一个 String 构造器</b>，于是包装底层异常时 cause 被丢掉了：
 * {@code throw new BusinessException("SQL执行失败：" + e.getMessage())}。
 *
 * <p>看起来只是少了点栈信息，但它<b>静默废掉了一个保护措施</b>：
 * {@link com.example.sqlquery.util.DbSourceCircuitBreaker} 判断"算不算基础设施失败"时
 * <b>沿 cause 链找</b>（{@code SQLTransientConnectionException} / {@code ConnectException} / Hikari 的异常…），
 * 而它拿到的永远是一个 <b>cause 为 null 的 BusinessException</b> →
 * 判定恒为"不是基础设施失败" → <b>熔断器永远不会打开</b>。
 *
 * <p>所以：<b>包装底层异常时一定要把 cause 带上</b>，否则上层所有基于异常类型的判断都会失效。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    /**
     * 带上原始异常。
     *
     * <p>给用户看的仍然只有 {@code message}（{@code GlobalExceptionHandler} 只取 message），
     * 但<b>异常链不能在业务层断掉</b> —— 不然熔断、重试、降级这些
     * "按异常类型决策"的逻辑全都拿不到依据。
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}