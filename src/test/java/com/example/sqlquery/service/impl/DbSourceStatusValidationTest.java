package com.example.sqlquery.service.impl;

import com.example.sqlquery.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * changeStatus 的状态值校验。
 *
 * <p>为什么可以不起 Spring 上下文：校验发生在方法<b>最前面</b>，
 * 早于 {@code getByIdAndUserId} 等任何依赖调用，所以把依赖全部传 null 也不会 NPE。
 * 这也顺带说明了一件事 —— <b>参数校验要放在方法入口</b>，
 * 它既能快速失败，也让这段逻辑可以脱离中间件做单元测试。
 */
class DbSourceStatusValidationTest {

    private final DbSourceServiceImpl service =
            new DbSourceServiceImpl(null, null, null, null);

    @Test
    void shouldRejectStatusOutOfRange() {
        assertThrows(BusinessException.class, () -> service.changeStatus(1L, 1L, 99));
        assertThrows(BusinessException.class, () -> service.changeStatus(1L, 1L, -1));
        assertThrows(BusinessException.class, () -> service.changeStatus(1L, 1L, 2));
    }

    @Test
    void shouldRejectNullStatus() {
        // 依赖全部为 null，如果校验漏了 null 会先 NPE 而不是抛 BusinessException
        assertThrows(BusinessException.class, () -> service.changeStatus(1L, 1L, null));
    }
}
