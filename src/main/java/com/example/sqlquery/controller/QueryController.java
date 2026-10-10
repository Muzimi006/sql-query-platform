package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.util.RateLimitUtil;
import com.example.sqlquery.vo.QueryVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
@Tag(name = "查询", description = "执行 SQL。四道防线 + 限流 + 超时 + 行数上限都在这条链路上")
public class QueryController {

    private final QueryService queryService;

    private final RateLimitUtil rateLimitUtil;

    /**
     * 单用户在一个窗口内允许执行的查询条数。
     *
     * <p><b>默认值取 60 而不是 10，是实测修正的结果。</b>
     * 原来写死 10 次/60 秒，看起来"更安全"，实际会拦住正常使用：
     * 调试一条 SQL 反复改几遍、或者演示时连跑几条，很容易就触顶，
     * 用户拿到的是「操作太频繁」而不是查询结果 —— 一个安全措施变成了功能故障。
     *
     * <p>阈值必须可配，因为合适的值取决于使用方式：
     * 单人调试、多人共用一个实例、给外部开放，三种情况下同一个数字不可能都对。
     * 真正需要在大流量下守住的是网关层限流，进程内这一层负责的是"拦住失控的脚本"。
     */
    @Value("${query.rate-limit.max-requests:60}")
    private int maxRequests;

    /** 限流窗口长度（秒）。 */
    @Value("${query.rate-limit.window-seconds:60}")
    private int windowSeconds;

    public QueryController(QueryService queryService, RateLimitUtil rateLimitUtil) {
        this.queryService = queryService;

        this.rateLimitUtil = rateLimitUtil;
    }


    @PostMapping("/execute")
    public Result<QueryVO> execute(@Valid @RequestBody QueryDTO dto){
        Long userId= UserContext.get();
        // 按用户分桶：限流的是"某个人刷得太快"，不是"整个平台的总量"
        String key = "rate:query:" + userId;
        if (!rateLimitUtil.tryAcquireSlidingWindow(key, maxRequests, windowSeconds, "query")) {
            throw new BusinessException("操作太频繁，请稍后再试");
        }
        return Result.success(queryService.execute(userId,dto));
    }
}
