package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.sqlquery.cache.DbSourceCacheInvalidator;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.util.AesUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 编辑数据源时的密码语义：**留空 = 不修改**。
 *
 * <h2>它修的是一个真实的功能障碍</h2>
 *
 * <p>数据源的密码出参<b>永远不下发</b>（{@code DbSourceVO} 里没有这个字段），
 * 所以编辑对话框里密码框天然是空的。
 * 而 {@code updateDbSource} 原来无条件用请求里的密码覆盖密文 ——
 * 后果是：<b>只想改个名字，也必须重新输一遍数据库密码</b>；
 * 更糟的是，如果密码框为空又恰好过了校验，数据源会被改成「密码为空」，
 * 下一次连接必然失败，而且报错和「密码填错」一模一样，很难查。
 *
 * <p>现在的语义：留空 → 回填<b>库里那条密文</b>；填了新密码 → 重新加密覆盖。
 * 「新增必须填密码」这条规则下沉到 {@code addDbSource}（DTO 被两个场景共用，
 * 放在 DTO 上会让编辑也被强制必填）。
 */
class DbSourcePasswordUpdateTest {

    private static final String OLD_CIPHER = "v1:OLD-CIPHERTEXT";

    private AesUtil aesUtil;
    private DbSourceServiceImpl service;

    /**
     * ⚠️ 纯单元测试里必须手动初始化 TableInfo 缓存：
     * {@code LambdaQueryWrapper.eq(DbSource::getName, ...)} 会立刻把方法引用解析成列名，
     * 而列名来自 MyBatis-Plus 的 TableInfo 缓存 —— 没有 Spring 上下文时它是空的，
     * 会抛 "can not find lambda cache for this entity"。
     * （本项目已经踩过三次，见 DataRetentionJobTest / PasswordRehashOnLoginTest。）
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DbSource.class);
    }

    @BeforeEach
    void setUp() {
        aesUtil = mock(AesUtil.class);
        DbSourceCacheInvalidator invalidator = mock(DbSourceCacheInvalidator.class);
        service = Mockito.spy(new DbSourceServiceImpl(aesUtil, null, null, invalidator, null));

        DbSource exist = new DbSource();
        exist.setId(1L);
        exist.setUserId(7L);
        exist.setName("老的");
        exist.setPasswordEncrypted(OLD_CIPHER);
        doReturn(exist).when(service).getById(1L);
        doReturn(0L).when(service).count(any());
        doReturn(true).when(service).updateById(any());
    }

    private DbSourceDTO dtoWithPassword(String password) {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("新名字");
        dto.setType("MYSQL");
        dto.setHost("127.0.0.1");
        dto.setPort(3306);
        dto.setDatabaseName("demo");
        dto.setUsername("reader");
        dto.setPassword(password);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private DbSource capturedUpdate() {
        ArgumentCaptor<DbSource> captor = ArgumentCaptor.forClass(DbSource.class);
        verify(service).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("⭐ 密码留空 → 保留库里的密文，且不会调用加密（只改名字也能保存）")
    void blankPasswordShouldKeepExistingCipher() {
        service.updateDbSource(7L, 1L, dtoWithPassword(null));

        assertEquals(OLD_CIPHER, capturedUpdate().getPasswordEncrypted(),
                "留空时必须回填库里那条密文，不能把 null/空串写进去");
        verify(aesUtil, never()).encrypt(anyString());
    }

    @Test
    @DisplayName("密码是空白字符也算留空（前端清空输入框会传空格或空串）")
    void whitespacePasswordShouldAlsoKeepExistingCipher() {
        service.updateDbSource(7L, 1L, dtoWithPassword("   "));

        assertEquals(OLD_CIPHER, capturedUpdate().getPasswordEncrypted());
        verify(aesUtil, never()).encrypt(anyString());
    }

    @Test
    @DisplayName("填了新密码 → 重新加密后覆盖")
    void newPasswordShouldBeEncrypted() {
        doReturn("v1:NEW-CIPHERTEXT").when(aesUtil).encrypt("newpass");

        service.updateDbSource(7L, 1L, dtoWithPassword("newpass"));

        assertEquals("v1:NEW-CIPHERTEXT", capturedUpdate().getPasswordEncrypted());
        verify(aesUtil).encrypt("newpass");
    }

    @Test
    @DisplayName("新增时密码必填（这条校验从 DTO 下沉到了服务层）")
    void createShouldRequirePassword() {
        assertThrows(BusinessException.class, () -> service.addDbSource(7L, dtoWithPassword("")));
        assertThrows(BusinessException.class, () -> service.addDbSource(7L, dtoWithPassword(null)));
        // 校验必须发生在任何依赖调用之前，否则就不是"参数校验"而是"办事办到一半才失败"
        verify(service, never()).count(any());
    }
}