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

    /**
     * 管理入口：只校验归属，<b>不</b>校验启用状态。
     * 修改 / 删除 / 启用停用都必须走这个方法 —— 否则停用后就再也启用不回来了。
     */
    DbSource getByIdAndUserId(Long userId, Long id);

    /**
     * 使用入口：在归属校验之外，额外要求数据源处于「启用」状态。
     * 执行 SQL、创建导出任务都必须走这个方法，避免「停用」形同虚设。
     */
    DbSource getEnabledByIdAndUserId(Long userId, Long id);

    boolean checkNameUnique(Long userId, String name);

    void changeStatus(Long userId, Long id, Integer status);
}
