package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.DbSourceDTO;

import com.example.sqlquery.service.DbSourceService;

import com.example.sqlquery.vo.DbSourceVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasource")
public class DbSourceController {

    private final DbSourceService dbSourceService;


    public DbSourceController(DbSourceService dbSourceService) {
        this.dbSourceService = dbSourceService;

    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody DbSourceDTO dto){
        Long userId = UserContext.get();
        dbSourceService.addDbSource(userId,dto);
        return Result.success();
    }

    @GetMapping
    public Result<List<DbSourceVO>> list(){
        Long userId = UserContext.get();
        List<DbSourceVO> list=dbSourceService.listByUserId(userId);
        return Result.success(list);
    }

    @GetMapping("/page")
    public Result<IPage<DbSourceVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        Long userId = UserContext.get();
        return Result.success(dbSourceService.pageByUserId(userId, page, size));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,@Valid @RequestBody DbSourceDTO dto){
        Long userId= UserContext.get();
        dbSourceService.updateDbSource(userId,id,dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.get();
        dbSourceService.deleteDbSource(userId, id);
        return Result.success();
    }

    @PostMapping("/test")
    public Result<Boolean> test(@Valid @RequestBody DbSourceDTO dto) {
        boolean ok = dbSourceService.testConnection(dto);
        return Result.success(ok);
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id,
                                     @RequestParam Integer status) {
        Long userId = UserContext.get();
        dbSourceService.changeStatus(userId, id, status);
        return Result.success();
    }

}

