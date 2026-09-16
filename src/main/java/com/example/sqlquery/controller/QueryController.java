package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.util.RateLimitUtil;
import com.example.sqlquery.vo.QueryVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;

    private final RateLimitUtil rateLimitUtil;

    public QueryController(QueryService queryService, RateLimitUtil rateLimitUtil) {
        this.queryService = queryService;

        this.rateLimitUtil = rateLimitUtil;
    }


    @PostMapping("/execute")
    public Result<QueryVO> execute(@Valid @RequestBody QueryDTO dto){
        Long userId= UserContext.get();
        String key = "rate:query:" + userId;
        if (!rateLimitUtil.tryAcquireSlidingWindow(key, 10, 60)) {
            throw new BusinessException("操作太频繁，请稍后再试");
        }
        return Result.success(queryService.execute(userId,dto));
    }
}
