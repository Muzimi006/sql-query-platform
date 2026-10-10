package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.Nl2SqlRequestDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.NaturalLanguageSqlService;
import com.example.sqlquery.util.RateLimitUtil;
import com.example.sqlquery.vo.SqlReviewRequestVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 自然语言转 SQL（并落成审核工单）。
 *
 * <p>任何登录用户都能用：它产生的只是一张<b>待审</b>工单，
 * 和他手写一条 SQL 提交上去是同一件事，权限要求也应当一致。
 */
@RestController
@RequestMapping("/api/nl2sql")
@Tag(name = "自然语言生成", description = "一句中文 → 生成 SQL → 过四道防线 → 落成待审工单。**默认关闭**")
public class NaturalLanguageSqlController {

    private final NaturalLanguageSqlService naturalLanguageSqlService;
    private final RateLimitUtil rateLimitUtil;

    /**
     * 单用户每分钟最多生成几次。
     *
     * <p>比工单提交严得多：<b>每一次都是一次付费的外部调用</b>，
     * 而且最长会占用 30 秒的 Web 线程（见 platform.llm.timeout-seconds）。
     * 不限流的话，一个人反复点就能把配额和线程一起烧掉。
     */
    @Value("${platform.llm.rate-limit.max-requests:5}")
    private int maxRequests;

    @Value("${platform.llm.rate-limit.window-seconds:60}")
    private int windowSeconds;

    public NaturalLanguageSqlController(NaturalLanguageSqlService naturalLanguageSqlService,
                                        RateLimitUtil rateLimitUtil) {
        this.naturalLanguageSqlService = naturalLanguageSqlService;
        this.rateLimitUtil = rateLimitUtil;
    }

    /**
     * 用一句中文需求换一张待审工单。
     *
     * <p>返回值就是工单本身 —— 里面同时有 {@code sqlText}（模型生成的 SQL）、
     * {@code generationPrompt}（用户原话）、{@code involvedTables} 和
     * {@code estimatedRows}。前端一个页面就能把这四样摆出来给审批人看。
     */
    @PostMapping("/ticket")
    public Result<SqlReviewRequestVO> ticket(@Valid @RequestBody Nl2SqlRequestDTO dto) {
        Long userId = UserContext.get();
        if (!rateLimitUtil.tryAcquireSlidingWindow(
                "rate:nl2sql:" + userId, maxRequests, windowSeconds)) {
            throw new BusinessException("生成太频繁，请稍后再试");
        }
        return Result.success(naturalLanguageSqlService.generateTicket(userId, dto));
    }

    /**
     * 这个功能当前可不可用。
     *
     * <p>前端据此决定要不要显示入口 —— 比"点进去才报错"好得多。
     *
     * <p>⚠️ 只回一个布尔值。<b>不回 base-url、不回模型名、更不回任何与 Key 有关的东西</b>：
     * 这些是配置细节，没有理由让任何登录用户看到。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(Map.of("available", naturalLanguageSqlService.available()));
    }
}
