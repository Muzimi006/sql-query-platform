package com.example.sqlquery.service.impl;

import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.util.AesUtil;
import com.example.sqlquery.util.ConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 数据源名称唯一性的「并发兜底」测试。
 *
 * <p>应用层的 {@code checkNameUnique} 是「先查再插」，<b>并发下挡不住</b>：
 * 两个请求可能同时查到「不重名」，然后双双插入。
 * 真正的兜底是数据库唯一索引 {@code uk_user_id_name}，
 * 而它抛出来的是 {@link DuplicateKeyException}。
 *
 * <p>如果不做翻译，这个异常会落到 {@code GlobalExceptionHandler}，
 * 被那里的 {@code handleDuplicateKey} 报成「用户名已存在」—— <b>提示是错的</b>。
 *
 * <p>本用例正是模拟这个时序：查重通过（返回 true），但写入时被约束拦下。
 */
class DbSourceNameUniqueTest {

    private DbSourceServiceImpl service;
    private DbSourceDTO dto;

    @BeforeEach
    void setUp() {
        AesUtil aesUtil = Mockito.mock(AesUtil.class);
        Mockito.when(aesUtil.encrypt(anyString())).thenReturn("v1:encrypted");

        ConnectionManager connectionManager = Mockito.mock(ConnectionManager.class);
        service = Mockito.spy(new DbSourceServiceImpl(aesUtil, null, null, connectionManager));

        dto = new DbSourceDTO();
        dto.setName("生产库");
        dto.setType("MySQL");
        dto.setHost("localhost");
        dto.setPort(3306);
        dto.setUsername("readonly");
        dto.setPassword("secret");
    }

    private DuplicateKeyException uniqueIndexViolation() {
        return new DuplicateKeyException(
                "Duplicate entry '1-生产库' for key 'data_source.uk_user_id_name'");
    }

    @Test
    void addShouldTranslateDuplicateKeyToBusinessMessage() {
        // 应用层查重通过 —— 模拟「两个并发请求同时查到不重名」
        Mockito.doReturn(true).when(service).checkNameUnique(anyLong(), anyString());
        // 但写入时被唯一索引拦下
        Mockito.doThrow(uniqueIndexViolation())
                .when(service).save(any(DbSource.class));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.addDbSource(1L, dto));
        assertEquals("数据源名称已存在", e.getMessage());
    }

    @Test
    void addShouldSucceedWhenNoConflict() {
        Mockito.doReturn(true).when(service).checkNameUnique(anyLong(), anyString());
        Mockito.doReturn(true).when(service).save(any(DbSource.class));

        service.addDbSource(1L, dto);

        Mockito.verify(service).save(any(DbSource.class));
    }
}
