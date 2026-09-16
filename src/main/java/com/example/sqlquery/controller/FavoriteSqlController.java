package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.FavoriteSqlDTO;
import com.example.sqlquery.service.FavoriteSqlService;

import com.example.sqlquery.vo.FavoriteSqlVO;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/favorite")
public class FavoriteSqlController {

    private final FavoriteSqlService favoriteSqlService;


    public FavoriteSqlController(FavoriteSqlService favoriteSqlService) {
        this.favoriteSqlService = favoriteSqlService;

    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody FavoriteSqlDTO dto) {
        Long userId = UserContext.get();
        favoriteSqlService.addFavorite(userId, dto);
        return Result.success();
    }

    @GetMapping
    public Result<List<FavoriteSqlVO>> list() {
        Long userId = UserContext.get();
        return Result.success(favoriteSqlService.listByUserId(userId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.get();
        favoriteSqlService.deleteFavorite(userId, id);
        return Result.success();
    }
}
