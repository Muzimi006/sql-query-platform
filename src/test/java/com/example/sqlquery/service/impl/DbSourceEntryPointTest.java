package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.DbSourceStatus;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.util.ConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁住「两个入口」的设计约定。
 *
 * <p>{@code DbSourceService} 把数据源查询拆成了两个入口：
 * <ul>
 *   <li>{@code getByIdAndUserId} —— 管理入口，只校验归属，<b>不</b>校验启用状态</li>
 *   <li>{@code getEnabledByIdAndUserId} —— 使用入口，额外要求启用</li>
 * </ul>
 *
 * <p>拆开的原因是：如果只有一个入口、并且在里面拦「已停用」，
 * 那么 {@code changeStatus} 自己也会被拦住 —— <b>数据源一旦停用就再也启用不回来了</b>。
 *
 * <p>本类用 Mockito spy 把 {@code getByIdAndUserId} 换成桩，从而在不碰
 * Redis / MySQL 的前提下覆盖这两条路径。
 */
class DbSourceEntryPointTest {

    private DbSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        ConnectionManager connectionManager = Mockito.mock(ConnectionManager.class);
        service = Mockito.spy(new DbSourceServiceImpl(null, null, null, connectionManager));
    }

    private DbSource sourceWithStatus(Integer status) {
        DbSource source = new DbSource();
        source.setId(1L);
        source.setUserId(1L);
        source.setStatus(status);
        return source;
    }

    private void stubExistingSource(int status) {
        Mockito.doReturn(sourceWithStatus(status)).when(service).getByIdAndUserId(1L, 1L);
    }

    // ---------- 使用入口：必须拦住已停用的数据源 ----------

    @Test
    void enabledEntryShouldRejectDisabledSource() {
        stubExistingSource(DbSourceStatus.DISABLED);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getEnabledByIdAndUserId(1L, 1L));
        assertEquals("数据源已停用", e.getMessage());
    }

    @Test
    void enabledEntryShouldPassEnabledSource() {
        stubExistingSource(DbSourceStatus.ENABLED);

        assertDoesNotThrow(() -> service.getEnabledByIdAndUserId(1L, 1L));
    }

    /** status 在库中可空，这里确认不会因为 null 抛 NPE。 */
    @Test
    void enabledEntryShouldRejectNullStatusWithoutNpe() {
        Mockito.doReturn(sourceWithStatus(null)).when(service)
                .getByIdAndUserId(1L, 1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getEnabledByIdAndUserId(1L, 1L));
        assertEquals("数据源已停用", e.getMessage());
    }

    // ---------- 管理入口：停用之后必须还能改回来 ----------

    /**
     * ★ 这条是防回归的关键。
     * 如果哪天有人把 changeStatus 改成走 {@code getEnabledByIdAndUserId}，
     * 那么对已停用的数据源再调用 changeStatus 会先被「已停用」拦住 ——
     * 也就是「停用之后再也启用不回来」。这条用例会立刻失败。
     */
    @Test
    void managementEntryShouldAllowReEnabling() {
        stubExistingSource(DbSourceStatus.DISABLED);
        Mockito.doReturn(true).when(service).updateById(Mockito.any(DbSource.class));

        assertDoesNotThrow(() -> service.changeStatus(1L, 1L, DbSourceStatus.ENABLED));
    }

    @Test
    void managementEntryShouldAllowDisabling() {
        stubExistingSource(DbSourceStatus.ENABLED);
        Mockito.doReturn(true).when(service).updateById(Mockito.any(DbSource.class));

        assertDoesNotThrow(() -> service.changeStatus(1L, 1L, DbSourceStatus.DISABLED));
    }
}
