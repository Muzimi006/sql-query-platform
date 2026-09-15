package com.example.sqlquery.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sqlquery.dto.FavoriteSqlDTO;
import com.example.sqlquery.entity.FavoriteSql;
import com.example.sqlquery.vo.FavoriteSqlVO;

import java.util.List;

public interface FavoriteSqlService extends IService<FavoriteSql> {

    void addFavorite(Long userId, FavoriteSqlDTO dto);

    List<FavoriteSqlVO> listByUserId(Long userId);

    void deleteFavorite(Long userId, Long id);
}
