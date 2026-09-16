package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.service.QueryHistoryService;

import com.example.sqlquery.vo.QueryHistoryVO;
import com.baomidou.mybatisplus.core.metadata.IPage;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
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
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
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
