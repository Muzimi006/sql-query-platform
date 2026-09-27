package com.example.sqlquery.controller;

import com.example.sqlquery.common.PageConstants;
import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.service.QueryHistoryService;

import com.example.sqlquery.vo.QueryHistoryVO;
import com.baomidou.mybatisplus.core.metadata.IPage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
// 同 DbSourceController：方法级参数校验必须先加 @Validated
@Validated
public class HistoryController {

    private final QueryHistoryService queryHistoryService;


    public HistoryController(QueryHistoryService queryHistoryService) {
        this.queryHistoryService = queryHistoryService;

    }

    @GetMapping
    public Result<List<QueryHistoryVO>> list() {
        Long userId = UserContext.get();
        return Result.success(queryHistoryService.listByUserId(userId));
    }

    @GetMapping("/page")
    public Result<IPage<QueryHistoryVO>> page(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = PageConstants.MAX_PAGE_SIZE,
                    message = "每页条数不能超过 " + PageConstants.MAX_PAGE_SIZE) long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dataSourceId) {
        Long userId = UserContext.get();
        return Result.success(queryHistoryService.pageByUserId(userId, page, size, status, dataSourceId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.get();
        queryHistoryService.deleteByIdAndUserId(id, userId);
        return Result.success();
    }

    @DeleteMapping
    public Result<Void> clear() {
        Long userId = UserContext.get();
        queryHistoryService.clearByUserId(userId);
        return Result.success();
    }
}
