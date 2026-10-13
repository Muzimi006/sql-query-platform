package com.example.sqlquery.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.sqlquery.common.PageConstants;
import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.SqlReviewDecisionDTO;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.SqlReviewService;
import com.example.sqlquery.infra.RateLimitUtil;
import com.example.sqlquery.vo.SqlReviewRequestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL 审核工单接口。
 *
 * <h2>接口分成两组，权限要求不同</h2>
 *
 * <ul>
 *   <li><b>申请侧</b>（submit / mine / 详情）：任何登录用户都能用。
 *       申请本身不改任何数据，只是往工单表里插一行 ——
 *       如果连申请都要管理员，工单系统就没有存在意义了。</li>
 *   <li><b>审批侧</b>（pending / approve / reject）：只有 ADMIN。
 *       权限判断放在服务层（{@code SqlReviewServiceImpl.requireAdmin}），
 *       不是放在这里 —— 否则以后多一个调用方（比如定时任务、内部接口）
 *       就多一条绕过检查的路径。</li>
 * </ul>
 *
 * <p>返回体一律是 HTTP 200 + 业务 code，和平台其它接口保持一致。
 */
@RestController
@RequestMapping("/api/sql-review")
// @Validated 是方法级参数校验的开关：只有加上它，@RequestParam 上的 @Min / @Max 才会生效。
// 这里刻意和 DbSourceController / HistoryController 保持一致 ——
// 两个分页接口拒绝非法参数、第三个静默钳制，是最容易让人踩坑的那种"不一致"
@Validated
@Tag(name = "审核工单", description = "写语句不再立即执行，而是先落一张待审工单")
public class SqlReviewController {

    private final SqlReviewService sqlReviewService;
    private final RateLimitUtil rateLimitUtil;

    /**
     * 单用户每分钟最多提交多少工单。
     *
     * <p>比查询的 60 次更严：每提交一次，平台就会拿<b>业务库</b>的连接跑一次 EXPLAIN
     * （见 SqlImpactAnalyzer），而那个连接池上限只有 5。
     * 任意登录用户无成本连点，既能把别人的查询连接池占满，又能往工单表里灌垃圾。
     *
     * <p>"谁都能申请"是对的（申请本身不改数据），但"谁都能无限量申请"不是。
     */
    @Value("${review.rate-limit.max-requests:20}")
    private int maxRequests;

    @Value("${review.rate-limit.window-seconds:60}")
    private int windowSeconds;

    public SqlReviewController(SqlReviewService sqlReviewService, RateLimitUtil rateLimitUtil) {
        this.sqlReviewService = sqlReviewService;
        this.rateLimitUtil = rateLimitUtil;
    }

    /**
     * 提交一条写语句的审核申请。
     *
     * <p>注意 DTO 里没有 generatedBy —— 走这个入口的一律记为人工手写。
     * AI 生成的那条走 {@code NaturalLanguageSqlService}，由服务端标记来源。
     */
    @Operation(summary = "提交工单",
            description = "任何登录用户都可以提交（申请本身不改任何数据）。"
                    + "⚠️ 受理范围由 platform.sql-permission.reviewable-types 决定，默认 INSERT/UPDATE/DELETE；"
                    + "提交 SELECT 会被告知「这是查询，不用审批」，提交 DDL 会被告知走 DBA 流程。")
    @PostMapping("/submit")
    public Result<SqlReviewRequestVO> submit(@Valid @RequestBody SqlReviewSubmitDTO dto) {
        Long userId = UserContext.get();
        // 按用户分桶限流：限的是"某个人刷得太快"，不是"整个平台的总量"
        if (!rateLimitUtil.tryAcquireSlidingWindow(
                "rate:sql-review:" + userId, maxRequests, windowSeconds, "sql-review")) {
            throw new BusinessException("提交太频繁，请稍后再试");
        }
        return Result.success(sqlReviewService.submit(userId, dto));
    }

    /** 我提交的工单。 */
    @Operation(summary = "我提交的工单", description = "任何登录用户，只返回自己的工单")
    @GetMapping("/mine")
    public Result<IPage<SqlReviewRequestVO>> mine(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1") long current,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = PageConstants.MAX_PAGE_SIZE,
                    message = "每页条数不能超过 " + PageConstants.MAX_PAGE_SIZE) long size) {
        return Result.success(sqlReviewService.pageMine(UserContext.get(), current, size));
    }

    /** 待审批队列（管理员）。 */
    @Operation(summary = "待审批队列", description = "🔒 **仅 ADMIN** 可用。"
            + "这里返回的是别人提交的待执行 SQL，属于敏感内容，所以按角色收口。")
    @GetMapping("/pending")
    public Result<IPage<SqlReviewRequestVO>> pending(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1") long current,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = PageConstants.MAX_PAGE_SIZE,
                    message = "每页条数不能超过 " + PageConstants.MAX_PAGE_SIZE) long size) {
        return Result.success(sqlReviewService.pagePending(current, size));
    }

    /** 工单详情：申请人看自己的，管理员看全部。 */
    @Operation(summary = "工单详情", description = "申请人本人或 ADMIN。"
            + "⚠️ 看别人的工单返回的是「工单不存在」而不是「无权查看」——"
            + "后者等于确认了这个 id 上确实有工单，拿一串 id 去试就能数出全部工单量。")
    @GetMapping("/{id}")
    public Result<SqlReviewRequestVO> detail(@PathVariable Long id) {
        return Result.success(sqlReviewService.detail(UserContext.get(), id));
    }

    /**
     * 批准。
     *
     * <p>{@code @RequestBody(required = false)}：批准可以不写意见，
     * 但请求体里有可能带别的字段（前端统一传对象更方便），所以允许空体。
     *
     * <p>⚠️ {@code @Valid} 不能省。DTO 上写了 {@code @Size(max = 500)}，
     * 但<b>没有 @Valid 的请求体参数不会被校验</b> —— 约束注解就成了一句注释。
     * 这个疏漏是靠一次真实 HTTP 调用发现的：传 600 字意见时，
     * 拿到的不是"意见过长"，而是服务层那句"工单已被处理过"，
     * 说明 {@code @Size} 根本没被执行过。
     * 而 DB 列是 {@code VARCHAR(500)}，一旦真把它写进去，
     * MySQL 严格模式抛的 {@code DataIntegrityViolationException}
     * 既不是 BusinessException 也不是 DuplicateKeyException，
     * 会掉进全局兜底变成"系统异常"。
     */
    @Operation(summary = "批准", description = "🔒 **仅 ADMIN** 可用，且**不能审批自己提交的工单**"
            + "（四眼原则；配置 review.allow-self-approve 可放开）。"
            + "⚠️ 批准是**异步**的：返回 200 只代表工单进入 APPROVED，"
            + "要轮询详情看 status 变成 EXECUTED / FAILED。")
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id,
                                @Valid @RequestBody(required = false) SqlReviewDecisionDTO dto) {
        sqlReviewService.approve(UserContext.get(), id, dto == null ? null : dto.getComment());
        return Result.success();
    }

    /**
     * 驳回。意见必填 —— 校验在服务层，这样绕过 HTTP 直接调服务时同样拦得住。
     */
    @Operation(summary = "驳回", description = "🔒 **仅 ADMIN** 可用。"
            + "⚠️ **意见必填** —— 申请人要靠它知道该改什么，否则只能原样再提交一遍。"
            + "自己驳回自己的工单是允许的（那本质上就是「撤回」，没有风险）。")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                               @Valid @RequestBody(required = false) SqlReviewDecisionDTO dto) {
        sqlReviewService.reject(UserContext.get(), id, dto == null ? null : dto.getComment());
        return Result.success();
    }
}
