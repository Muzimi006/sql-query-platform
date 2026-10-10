package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.common.UserRole;
import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.QueryHistoryService;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.util.ConnectionManager;
import com.example.sqlquery.util.DbSourceCircuitBreaker;
import com.example.sqlquery.util.QueryConcurrencyGuard;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.vo.QueryVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.SQLTransientConnectionException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询链路的失败传播 —— 特别盯着"熔断器认不认得出基础设施失败"这条链。
 *
 * <h2>为什么要专门写这个类</h2>
 *
 * <p>审计发现：熔断器（三层保护里的第二层）**在真实调用链上永远不会打开**。
 * 原因是 {@code QueryServiceImpl} 把 {@code SQLException} 包成 {@code BusinessException} 时
 * <b>只传了 message、把 cause 丢掉了</b>，而熔断器靠"沿 cause 链认连接类异常"判断 ——
 * 拿到一个 cause 为 null 的异常，判定恒为"不是基础设施失败"。
 *
 * <p>所以这个类里用<b>真实的</b> {@link DbSourceCircuitBreaker}（不是 mock）：
 * 只有真熔断器，才能验"包装完的异常它还认不认得出"。
 * 用 mock 的话，这条链上的断点会被 mock 掩盖掉。
 */
class QueryServiceImplTest {

    private static final Long DATA_SOURCE_ID = 7L;
    private static final Long USER_ID = 1L;

    private DbSourceService dbSourceService;
    private QueryHistoryService queryHistoryService;
    private ConnectionManager connectionManager;
    private SqlPermissionService sqlPermissionService;
    private QueryConcurrencyGuard concurrencyGuard;
    private DbSourceCircuitBreaker circuitBreaker;
    private QueryServiceImpl service;
    private SqlPlatformMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        dbSourceService = Mockito.mock(DbSourceService.class);
        queryHistoryService = Mockito.mock(QueryHistoryService.class);
        connectionManager = Mockito.mock(ConnectionManager.class);
        sqlPermissionService = Mockito.mock(SqlPermissionService.class);
        concurrencyGuard = Mockito.mock(QueryConcurrencyGuard.class);

        // ⭐ 真实熔断器：阈值 3、熔断 60 秒
        circuitBreaker = new DbSourceCircuitBreaker(3, 60_000,
                new SqlPlatformMetrics(new SimpleMeterRegistry()));

        registry = new SimpleMeterRegistry();
        metrics = new SqlPlatformMetrics(registry);
        service = new QueryServiceImpl(dbSourceService, queryHistoryService, connectionManager,
                metrics, sqlPermissionService, concurrencyGuard, circuitBreaker);

        DbSource dbSource = new DbSource();
        dbSource.setId(DATA_SOURCE_ID);
        dbSource.setType("MySQL");
        Mockito.when(dbSourceService.getEnabledByIdAndUserId(USER_ID, DATA_SOURCE_ID)).thenReturn(dbSource);
        Mockito.when(sqlPermissionService.getAllowedTypes(ArgumentMatchers.any())).thenReturn(Set.of(SqlType.SELECT));
        Mockito.when(concurrencyGuard.tryAcquire(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(QueryConcurrencyGuard.RejectReason.NONE);

        UserContext.set(USER_ID);
        UserContext.setRole(UserRole.ADMIN);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private QueryDTO dto() {
        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(DATA_SOURCE_ID);
        dto.setSql("select 1");
        return dto;
    }

    @Test
    @DisplayName("⭐ 底层是连接类异常时，包装后的 BusinessException **必须带上 cause**")
    void wrappedExceptionMustCarryTheCause() throws Exception {
        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any()))
                .thenThrow(new SQLTransientConnectionException("池子拿不到连接"));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.execute(USER_ID, dto()));

        // 这就是审计发现的断点：以前 cause 是 null，于是下面那条断言永远不成立
        assertInstanceOf(SQLTransientConnectionException.class, thrown.getCause(),
                "包装底层异常时必须带上 cause —— 否则熔断器认不出基础设施失败，永远不会打开");
    }

    @Test
    @DisplayName("⭐⭐ 连续连不上 3 次 → 真实熔断器必须打开（这条在修复前必红）")
    void repeatedConnectionFailureShouldOpenTheBreaker() throws Exception {
        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any()))
                .thenThrow(new SQLTransientConnectionException("池子拿不到连接"));

        for (int i = 0; i < 3; i++) {
            assertThrows(BusinessException.class, () -> service.execute(USER_ID, dto()));
        }

        assertFalse(circuitBreaker.allowRequest(DATA_SOURCE_ID),
                "连续 3 次连接类失败之后必须熔断，否则「不让一个坏掉的库拖垮平台」就是空话");
    }

    @Test
    @DisplayName("⭐ 查询历史写不进去，绝不能把一次成功的执行报成「系统异常」")
    void historySaveFailureMustNotChangeTheOutcome() throws Exception {
        // 业务库是好的：一行一列
        java.sql.ResultSetMetaData metaData = Mockito.mock(java.sql.ResultSetMetaData.class);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnLabel(1)).thenReturn("v");
        java.sql.ResultSet resultSet = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(resultSet.next()).thenReturn(true, false);
        Mockito.when(resultSet.getObject(1)).thenReturn(1);
        java.sql.Statement statement = Mockito.mock(java.sql.Statement.class);
        Mockito.when(statement.executeQuery(ArgumentMatchers.anyString())).thenReturn(resultSet);
        java.sql.Connection connection = Mockito.mock(java.sql.Connection.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any())).thenReturn(connection);

        // 而平台库（写历史）挂了
        Mockito.doThrow(new RuntimeException("平台库不可用"))
                .when(queryHistoryService).saveHistory(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyLong(), ArgumentMatchers.any());

        // ⭐ 必须正常返回：那条 SQL **已经真的执行了**，
        //    把它报成失败会让用户重试 —— 而写语句重试就是**重复改一次数据**。
        QueryVO vo = service.execute(USER_ID, dto());

        assertNotNull(vo, "历史写失败不该影响这次执行的结论");
        assertNotNull(vo.getColumns());
        // 但也不能静默：失败要有计数器（这个类用的是真实 metrics，所以断言计数值）
        assertEquals(1.0, registry.get(SqlPlatformMetrics.HISTORY_SAVE_FAILED).counter().count(),
                "历史写失败必须留下一个可告警的计数器");
    }

    @Test
    @DisplayName("用户写错 SQL（业务失败）不会把数据源熔断")
    void businessFailureShouldNotOpenTheBreaker() {
        DbSource dbSource = new DbSource();
        dbSource.setId(DATA_SOURCE_ID);
        dbSource.setType("MySQL");
        Mockito.when(dbSourceService.getEnabledByIdAndUserId(USER_ID, DATA_SOURCE_ID)).thenReturn(dbSource);

        // 校验阶段就被拦下（不是 select）—— 连数据库都没碰
        Mockito.when(sqlPermissionService.getAllowedTypes(ArgumentMatchers.any())).thenReturn(Set.of(SqlType.UPDATE));

        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> service.execute(USER_ID, dto()));
        }

        assertTrue(circuitBreaker.allowRequest(DATA_SOURCE_ID),
                "业务失败不该熔断数据源（否则一个人连写错 5 次 SQL 就能把整个库封掉）");
    }
}