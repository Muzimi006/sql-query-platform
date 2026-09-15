package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sqlquery.dto.FavoriteSqlDTO;
import com.example.sqlquery.entity.FavoriteSql;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.FavoriteSqlMapper;
import com.example.sqlquery.service.FavoriteSqlService;
import com.example.sqlquery.vo.FavoriteSqlVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FavoriteSqlServiceImpl extends ServiceImpl<FavoriteSqlMapper, FavoriteSql> implements FavoriteSqlService {

    @Override
    public void addFavorite(Long userId, FavoriteSqlDTO dto) {
        FavoriteSql favorite = new FavoriteSql();
        favorite.setUserId(userId);
        favorite.setDataSourceId(dto.getDataSourceId());
        favorite.setSqlText(dto.getSqlText());
        favorite.setRemark(dto.getRemark());
        save(favorite);
    }

    @Override
    public List<FavoriteSqlVO> listByUserId(Long userId) {
        LambdaQueryWrapper<FavoriteSql> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FavoriteSql::getUserId,userId)
                .orderByDesc(FavoriteSql::getCreatedAt);
        return list(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteFavorite(Long userId, Long id) {
        FavoriteSql exist = getById(id);
        if(exist == null || !exist.getUserId().equals(userId)){
            throw new BusinessException("收藏不存在");
        }
        removeById(id);
    }

    private FavoriteSqlVO toVO(FavoriteSql favorite){
        FavoriteSqlVO vo = new FavoriteSqlVO();
        vo.setId(favorite.getId());
        vo.setDataSourceId(favorite.getDataSourceId());
        vo.setSqlText(favorite.getSqlText());
        vo.setRemark(favorite.getRemark());
        vo.setCreatedAt(favorite.getCreatedAt());
        return vo;
    }
}
