package com.example.sqlquery.controller;


import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.service.ExportService;

import com.example.sqlquery.vo.ExportTaskVO;

import com.example.sqlquery.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;


    public ExportController(ExportService exportService) {
        this.exportService = exportService;

    }

    @PostMapping
    public Result<ExportTaskVO> create(@Valid @RequestBody ExportTaskDTO dto) {
        Long userId = UserContext.get();
        return Result.success(exportService.createTask(userId, dto));
    }

    @GetMapping
    public Result<List<ExportTaskVO>> list() {
        Long userId = UserContext.get();
        return Result.success(exportService.listByUserId(userId));
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        Long userId = UserContext.get();
        String filePath = exportService.getDownloadPath(userId, id);
        File file = new File(filePath);
        if (!file.exists()) {
            throw new BusinessException("文件不存在");
        }
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=" + file.getName());
        Files.copy(file.toPath(), response.getOutputStream());
    }
}
