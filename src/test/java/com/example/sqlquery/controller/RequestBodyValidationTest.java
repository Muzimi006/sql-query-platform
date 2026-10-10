package com.example.sqlquery.controller;

import jakarta.validation.Valid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全项目级回归：<b>每一个 {@code @RequestBody} 参数都必须带 {@code @Valid}。</b>
 *
 * <h2>为什么值得单独写一个测试</h2>
 *
 * <p>这是一个<b>不会报错、只会静默失效</b>的错误：DTO 上写了
 * {@code @Size} / {@code @NotBlank}，但控制器参数忘了 {@code @Valid}，
 * 校验就完全不执行 —— <b>那些注解退化成了注释</b>。
 * 编译通过、启动正常、请求也返回 200，没有任何地方会提示你。
 *
 * <p>本项目就是靠一次真实 HTTP 调用才发现的：给审批接口传一段 600 字的意见，
 * 拿到的不是"意见过长"，而是服务层那句"工单已被处理过"——
 * 说明 {@code @Size(max = 500)} 从头到尾没被执行过。
 * 而数据库列是 {@code VARCHAR(500)}，真写进去时 MySQL 严格模式抛的
 * {@code DataIntegrityViolationException} 既不是 BusinessException
 * 也不是 DuplicateKeyException，会掉进全局兜底变成"系统异常"。
 *
 * <h2>为什么要遍历所有控制器，而不是只测这一个</h2>
 *
 * <p>"某个控制器忘了加 @Valid"这件事和具体哪个控制器无关，是<b>一类</b>错误。
 * 只给刚出问题的那一个补测试，下一个新控制器照样会漏。
 */
class RequestBodyValidationTest {

    /**
     * 项目里所有的控制器。
     *
     * <p>⚠️ <b>新增控制器时要把它加进来</b> —— 这也是这个测试唯一的维护成本。
     * 之所以不靠扫描包，是因为单元测试里扫描 classpath 会依赖构建产物的布局，
     * 在 IDE 和 maven 下行为不一致，反而容易变成"看起来在检查、其实一个也没扫到"。
     */
    private static final List<Class<?>> CONTROLLERS = List.of(
            AuthController.class,
            DbSourceController.class,
            QueryController.class,
            HistoryController.class,
            FavoriteSqlController.class,
            ExportController.class,
            SqlReviewController.class,
            NaturalLanguageSqlController.class);

    @Test
    @DisplayName("所有 @RequestBody 参数都必须带 @Valid，否则 DTO 上的约束注解等于一句注释")
    void everyRequestBodyMustCarryValid() {
        List<String> missing = new ArrayList<>();

        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                for (int i = 0; i < parameterAnnotations.length; i++) {
                    boolean isBody = false;
                    boolean hasValid = false;
                    for (Annotation annotation : parameterAnnotations[i]) {
                        if (annotation instanceof RequestBody) {
                            isBody = true;
                        }
                        if (annotation instanceof Valid) {
                            hasValid = true;
                        }
                    }
                    if (isBody && !hasValid) {
                        missing.add(controller.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "这些 @RequestBody 参数没有 @Valid，其 DTO 上的校验注解不会生效：" + missing);
    }
}
