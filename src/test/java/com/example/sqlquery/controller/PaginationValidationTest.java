package com.example.sqlquery.controller;

import com.example.sqlquery.common.PageConstants;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.executable.ExecutableValidator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页参数约束的回归测试。
 *
 * <p>用 Bean Validation 的 {@link ExecutableValidator} 直接校验方法参数，
 * <b>不需要启动 Spring 上下文</b>，因此不依赖 MySQL / Redis / RabbitMQ，可以进 CI 的单元测试层。
 *
 * <p>它验证的是「约束注解写在正确的位置上、并且真的会拦住」；
 * 「接口最终返回什么提示」由 {@code GlobalExceptionHandler} 负责，需要集成测试覆盖。
 */
class PaginationValidationTest {

    private final ExecutableValidator validator =
            Validation.buildDefaultValidatorFactory().getValidator().forExecutables();

    private Set<ConstraintViolation<DbSourceController>> validateDbSourcePage(long page, long size)
            throws Exception {
        Method method = DbSourceController.class.getMethod("page", long.class, long.class);
        return validator.validateParameters(new DbSourceController(null), method, new Object[]{page, size});
    }

    private Set<ConstraintViolation<HistoryController>> validateHistoryPage(long page, long size)
            throws Exception {
        Method method = HistoryController.class.getMethod(
                "page", long.class, long.class, String.class, Long.class);
        return validator.validateParameters(
                new HistoryController(null), method, new Object[]{page, size, null, null});
    }

    @Test
    void shouldRejectSizeOverLimit() throws Exception {
        assertTrue(validateDbSourcePage(1, PageConstants.MAX_PAGE_SIZE).isEmpty(),
                "正好等于上限应该放行");
        assertFalse(validateDbSourcePage(1, PageConstants.MAX_PAGE_SIZE + 1).isEmpty(),
                "超过上限应该被拦下");
        assertFalse(validateDbSourcePage(1, 1_000_000).isEmpty(),
                "超大 size 应该被拦下");
    }

    @Test
    void shouldRejectNonPositivePageOrSize() throws Exception {
        assertFalse(validateDbSourcePage(0, 10).isEmpty(), "page=0 应该被拦下");
        assertFalse(validateDbSourcePage(-1, 10).isEmpty(), "page 为负应该被拦下");
        assertFalse(validateDbSourcePage(1, 0).isEmpty(), "size=0 应该被拦下");
    }

    @Test
    void shouldAcceptValidPagination() throws Exception {
        assertTrue(validateDbSourcePage(1, 10).isEmpty());
    }

    /** 两个分页接口必须保持一致的防护等级，这条用例专门防止「只改了一个」。 */
    @Test
    void historyPageShouldHaveSameConstraints() throws Exception {
        assertTrue(validateHistoryPage(1, 10).isEmpty());
        assertFalse(validateHistoryPage(1, PageConstants.MAX_PAGE_SIZE + 1).isEmpty(),
                "HistoryController 的 size 上限漏了");
        assertFalse(validateHistoryPage(0, 10).isEmpty(),
                "HistoryController 的 page 下限漏了");
    }
}
