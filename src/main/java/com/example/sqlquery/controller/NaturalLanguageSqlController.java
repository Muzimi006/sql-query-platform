package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.Nl2SqlRequestDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.NaturalLanguageSqlService;
import com.example.sqlquery.util.Nl2SqlConcurrencyGuard;
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
 *
 * <p>入口有两道闸：<b>频率</b>限流（每人每分钟几次）和<b>并发</b>上限
 * （{@link Nl2SqlConcurrencyGuard}，同时在跑几个）。
 * 前者拦"脚本刷"，后者拦"一次生成要等 30 秒、几个请求就把线程占满"。
 */
@RestController
@RequestMapping("/api/nl2sql")
@Tag(name = "自然语言生成", description = "一句中文 → 生成 SQL → 过四道防线 → 落成待审工单。**默认关闭**")
public class NaturalLanguageSqlController {

    private final NaturalLanguageSqlService naturalLanguageSqlService;
    private final RateLimitUtil rateLimitUtil;
    private final Nl2SqlConcurrencyGuard concurrencyGuard;

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
                                        RateLimitUtil rateLimitUtil,
                                        Nl2SqlConcurrencyGuard concurrencyGuard) {
        this.concurrencyGuard = concurrencyGuard;
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
                "rate:nl2sql:" + userId, maxRequests, windowSeconds, "nl2sql")) {
            throw new BusinessException("生成太频繁，请稍后再试");
        }

        // 频率限制过后还有一道并发限制，两者不能互相替代：
        // 上面那道管"一分钟点了几次"，这一道管"此刻有几个请求还挂在模型调用上"。
        // 一次生成最长占 30 秒 Web 线程，只有频率限制时 5 次/分钟 = 5 个线程同时在等。
        if (!concurrencyGuard.tryAcquire()) {
            throw new BusinessException("当前生成任务较多，请稍后再试");
        }
        try {
            return Result.success(naturalLanguageSqlService.generateTicket(userId, dto));
        } finally {
            // 必须 finally：抛异常（包括模型返回不合规被拒）也要把额度还回去，
            // 否则每失败一次就永久少一个额度，最后整个功能卡死
            concurrencyGuard.release();
        }
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
