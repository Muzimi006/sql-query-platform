package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.FavoriteSqlDTO;
import com.example.sqlquery.service.FavoriteSqlService;
import com.example.sqlquery.util.JwtUtil;
import com.example.sqlquery.vo.FavoriteSqlVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/favorite")
public class FavoriteSqlController {

    private final FavoriteSqlService favoriteSqlService;
    private final JwtUtil jwtUtil;

    public FavoriteSqlController(FavoriteSqlService favoriteSqlService, JwtUtil jwtUtil) {
        this.favoriteSqlService = favoriteSqlService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody FavoriteSqlDTO dto, HttpServletRequest request) {
        Long userId = UserContext.get();
        favoriteSqlService.addFavorite(userId, dto);
        return Result.success();
    }

    @GetMapping
    public Result<List<FavoriteSqlVO>> list(HttpServletRequest request) {
        Long userId = UserContext.get();
        return Result.success(favoriteSqlService.listByUserId(userId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = UserContext.get();
        favoriteSqlService.deleteFavorite(userId, id);
        return Result.success();
    }
}
