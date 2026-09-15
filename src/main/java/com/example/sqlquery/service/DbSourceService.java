package com.example.sqlquery.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.vo.DbSourceVO;

import java.util.List;

public interface DbSourceService extends IService<DbSource> {

    void addDbSource(Long userId, DbSourceDTO dto);

    List<DbSourceVO> listByUserId(Long userId);

    void updateDbSource(Long userId,Long id,DbSourceDTO dto);

    void deleteDbSource(Long userId,Long id);

    boolean testConnection(DbSourceDTO dto);

    IPage<DbSourceVO> pageByUserId(Long userId, long current, long size);

    DbSource getByIdAndUserId(Long userId, Long id);

    boolean checkNameUnique(Long userId, String name);

    void changeStatus(Long userId, Long id, Integer status);
}
