package com.example.sqlquery.controller;

import com.example.sqlquery.common.PageConstants;
import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.dto.DbSourceDTO;

import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.infra.RateLimitUtil;
import org.springframework.beans.factory.annotation.Value;

import com.example.sqlquery.vo.DbSourceVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasource")
@Tag(name = "数据源", description = "连接信息管理。密码 AES-GCM 加密后入库，**出参永不下发密码**")
// @Validated 是方法级参数校验的开关：只有加上它，@RequestParam 上的 @Min / @Max 才会生效
@Validated
public class DbSourceController {

    private final DbSourceService dbSourceService;
    private final RateLimitUtil rateLimitUtil;

    /**
     * 测试连接的限流：**这个接口以前完全没有限流，而它是"让服务器去连任意 host:port"**。
     *
     * <p>一次调用最多占一个 Tomcat 线程 5 秒（JdbcUrlUtil 的 connectTimeout），
     * 不需要高并发就能把线程池慢慢吃掉；而且它天然是一个端口探测面。
     * <b>能连出去的接口，一定要有次数上限。</b>
     */
    @Value("${datasource.test-connection.rate-limit.max-requests:10}")
    private int testConnectionMaxRequests;

    @Value("${datasource.test-connection.rate-limit.window-seconds:60}")
    private int testConnectionWindowSeconds;


    public DbSourceController(DbSourceService dbSourceService, RateLimitUtil rateLimitUtil) {
        this.dbSourceService = dbSourceService;
        this.rateLimitUtil = rateLimitUtil;
    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody DbSourceDTO dto){
        Long userId = UserContext.get();
        dbSourceService.addDbSource(userId,dto);
        return Result.success();
    }

    /**
     * 支持的数据库类型，供前端下拉框渲染。
     *
     * <p>前端不写死类型清单，是因为"后端支持了但下拉框里没有"这种不一致
     * 不会有任何报错 —— 用户只会觉得这个功能不存在。
     */
    @GetMapping("/dialects")
    public Result<List<SqlDialect.Option>> dialects() {
        return Result.success(SqlDialect.options());
    }

    @GetMapping
    public Result<List<DbSourceVO>> list(){
        Long userId = UserContext.get();
        List<DbSourceVO> list=dbSourceService.listByUserId(userId);
        return Result.success(list);
    }

    @GetMapping("/page")
    public Result<IPage<DbSourceVO>> page(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = PageConstants.MAX_PAGE_SIZE,
                    message = "每页条数不能超过 " + PageConstants.MAX_PAGE_SIZE) long size) {
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
        // 按用户分桶：拦的是"一个人反复拿服务器去探端口"，不是全平台总量
        String key = "rate:datasource-test:" + UserContext.get();
        if (!rateLimitUtil.tryAcquireSlidingWindow(
                key, testConnectionMaxRequests, testConnectionWindowSeconds, "datasource-test")) {
            throw new BusinessException("操作太频繁，请稍后再试");
        }
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

