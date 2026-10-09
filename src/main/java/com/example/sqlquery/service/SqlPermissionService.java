package com.example.sqlquery.service;

import com.example.sqlquery.config.SqlPermissionProperties;
import com.example.sqlquery.util.SqlType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 按角色取 SQL 类型白名单。
 *
 * <h2>核心约定：查不到就是全拒（fail-closed）</h2>
 *
 * <p>未知角色、{@code null} 角色、配置里没写的角色，一律返回<b>空集合</b>，
 * 也就是"什么都不能执行"。这与 {@code SqlValidateUtil} 白名单缺失时全拒是同一个原则：
 * <b>权限这种默认值，猜错的代价是不对称的</b> ——
 * 猜"允许"错了是越权，猜"拒绝"错了只是用户来问一句。
 *
 * <p>这里改掉了旧实现的行为。旧实现是"未知角色兜底成 SELECT"，
 * 有两个问题：
 * <ul>
 *   <li>数据库里角色被写错一个字母（{@code Admni}），用户<b>静默地</b>拿到 SELECT 权限，
 *       没有任何地方会报错；</li>
 *   <li>传 {@code null} 时（例如 token 里没有 role 字段）会走
 *       {@code Map.of(...).getOrDefault(null, ...)}，而不可变 Map <b>不接受 null 键</b>，
 *       直接抛 NPE，被全局处理器翻译成「系统异常」—— 一个权限问题伪装成了服务端故障。</li>
 * </ul>
 */
@Slf4j
@Service
public class SqlPermissionService {

    private final SqlPermissionProperties properties;

    public SqlPermissionService(SqlPermissionProperties properties) {
        this.properties = properties;
    }

    /**
     * 取该角色允许执行的语句类型。
     *
     * @param role 角色名，大小写不敏感；为 {@code null} 或空白时返回空集合
     * @return <b>不允许为空</b>的集合；空集合表示"什么都不允许"
     */
    public Set<SqlType> getAllowedTypes(String role) {
        if (role == null || role.isBlank()) {
            log.warn("请求没有携带角色信息，按「不允许执行任何 SQL」处理");
            return Set.of();
        }

        Set<SqlType> allowed = properties.getRoleWhitelist().get(role.trim().toUpperCase(Locale.ROOT));
        if (allowed == null) {
            log.warn("角色 {} 在权限配置里不存在，按「不允许执行任何 SQL」处理。"
                    + "如果是新增角色，请同步配置 platform.sql-permission.role-whitelist", role);
            return Set.of();
        }

        // 返回不可变副本：调用方拿到之后改不动它，避免"某处不小心改了全局配置"
        return Set.copyOf(allowed);
    }
}
