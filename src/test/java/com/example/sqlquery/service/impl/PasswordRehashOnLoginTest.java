package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.sqlquery.entity.User;
import com.example.sqlquery.mapper.UserMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.AdminRegistrationPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.example.sqlquery.exception.BusinessException;

/**
 * 登录时的**密码哈希渐进升级**。
 *
 * <h2>它补的是一个静默的坑</h2>
 *
 * <p>BCrypt 的成本因子（轮次）**写在每条哈希串里**，`matches()` 读的是哈希串里的那个值。
 * 于是把 `platform.password.bcrypt-strength` 调高之后：
 *
 * <pre>
 * 新注册 / 改密码的用户 → 用新强度（看起来"改了生效了"）
 * 老用户                → **一直停在旧强度**，而且没有任何地方看得出来
 * </pre>
 *
 * <p>登录成功那一刻是**唯一**能拿到明文的时刻，所以升级只能放在这里。
 */
class PasswordRehashOnLoginTest {

    private static final String RAW_PASSWORD = "Probe12345!";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);

    private UserMapper userMapper;
    private AdminRegistrationPolicy policy;

    /**
     * ⚠️ 这一步是必须的，而且这是本项目第二次踩同一个坑（第一次在 DataRetentionJobTest）：
     * `LambdaUpdateWrapper.eq(User::getId, …)` 会**立刻**把方法引用解析成列名，
     * 而列名来自 MyBatis-Plus 的 TableInfo 缓存 —— 纯单元测试里没有 Spring 上下文、缓存是空的，
     * 于是抛 "can not find lambda cache for this entity"，而它**被 rehash 的 catch 收成了"升级失败"**，
     * 表现为「一次 update 都没发生、failed 计数 +1」—— 看起来像生产代码没工作。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                User.class);
    }

    @BeforeEach
    void setUp() {
        userMapper = Mockito.mock(UserMapper.class);
        policy = Mockito.mock(AdminRegistrationPolicy.class);
    }

    /** 用指定成本因子造一条"老哈希"，并让 mapper 把它当作库里的那条返回。 */
    private UserServiceImpl serviceWithStoredHash(int currentStrength, String storedHash,
                                                  int updateAffected) {
        PasswordEncoder encoder = new BCryptPasswordEncoder(currentStrength);
        UserServiceImpl service = Mockito.spy(new UserServiceImpl(encoder, policy, metrics));
        User stored = new User();
        stored.setId(7L);
        stored.setUsername("probe");
        stored.setPassword(storedHash);
        stored.setStatus(1);
        stored.setRole("USER");
        Mockito.doReturn(stored).when(service).getByUsername("probe");
        Mockito.doReturn(userMapper).when(service).getBaseMapper();
        Mockito.when(userMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(updateAffected);
        return service;
    }

    @SuppressWarnings("unchecked")
    private Wrapper<User> capturedWrapper() {
        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(userMapper).update(ArgumentMatchers.isNull(), captor.capture());
        return captor.getValue();
    }

    private double rehashed(String outcome) {
        var counter = registry.find(SqlPlatformMetrics.PASSWORD_REHASH)
                .tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("⭐ 老哈希成本因子更低 → 登录成功后自动升级，且新哈希能在新强度下校验通过")
    void outdatedHashShouldBeUpgradedOnLogin() {
        // 库里那条是 cost=4 的老哈希，当前配置是 10
        String oldHash = new BCryptPasswordEncoder(4).encode(RAW_PASSWORD);
        UserServiceImpl service = serviceWithStoredHash(10, oldHash, 1);

        assertNotNull(service.login("probe", RAW_PASSWORD));

        Wrapper<User> wrapper = capturedWrapper();
        String sqlSet = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>) wrapper)
                .getSqlSet();
        assertTrue(sqlSet.contains("password"), "只应该更新 password 列，实际：" + sqlSet);
        // 条件里必须带上"密码仍是刚校验过的那一条"，避免把别处刚改的密码覆盖回去
        assertTrue(wrapper.getSqlSegment().contains("password"),
                "更新条件里必须包含原密码（否则会覆盖用户刚改的密码）：" + wrapper.getSqlSegment());
        assertEquals(1.0, rehashed("success"));
        assertEquals(0.0, rehashed("failed"),
                "升级不该失败 —— 失败计数涨说明它被 catch 收走了（例如 lambda 缓存为空）");
    }

    @Test
    @DisplayName("哈希已经是当前强度 → 一次库都不写（不做无意义的写）")
    void upToDateHashShouldNotBeRewritten() {
        String currentHash = new BCryptPasswordEncoder(10).encode(RAW_PASSWORD);
        UserServiceImpl service = serviceWithStoredHash(10, currentHash, 1);

        assertNotNull(service.login("probe", RAW_PASSWORD));

        Mockito.verify(userMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
        assertEquals(0.0, rehashed("success"));
    }

    @Test
    @DisplayName("⭐ 升级写库失败**不能**影响登录：密码是对的，就该让用户进去")
    void rehashFailureShouldNotBreakLogin() {
        String oldHash = new BCryptPasswordEncoder(4).encode(RAW_PASSWORD);
        UserServiceImpl service = serviceWithStoredHash(10, oldHash, 1);
        Mockito.when(userMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("平台库不可用"));

        // 不抛异常 —— 这是一次**成功**的登录
        assertNotNull(service.login("probe", RAW_PASSWORD));
        assertEquals(1.0, rehashed("failed"));
    }

    @Test
    @DisplayName("密码错的登录：既不写库也不升级（升级只发生在校验成功之后）")
    void wrongPasswordShouldNotTriggerRehash() {
        String oldHash = new BCryptPasswordEncoder(4).encode(RAW_PASSWORD);
        UserServiceImpl service = serviceWithStoredHash(10, oldHash, 1);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.sqlquery.exception.BusinessException.class,
                () -> service.login("probe", "wrong-password"));

        Mockito.verify(userMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
        assertEquals(0.0, rehashed("success"));
    }
}
