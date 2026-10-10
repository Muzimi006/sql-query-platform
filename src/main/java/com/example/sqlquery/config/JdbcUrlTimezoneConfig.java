package com.example.sqlquery.config;

import com.example.sqlquery.util.JdbcUrlUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code datasource.mysql.server-timezone} 注入给 {@link JdbcUrlUtil}。
 *
 * <h2>为什么要有这么一个"只有一行"的配置类</h2>
 *
 * <p>{@link JdbcUrlUtil} 是纯静态工具，被连接池（{@code ConnectionManager}）
 * 和「测试连接」（{@code DbSourceServiceImpl}）两处调用 —— 它们都在构造业务库连接串。
 * 要让时区可配，只有三条路：
 *
 * <ol>
 *   <li>给两个调用方各加一个构造参数：<b>改动面比收益大</b>，
 *       而且这两个类都已经有 4~5 个依赖，再多一个"拼 URL 的细节"并不合适；</li>
 *   <li>让工具类自己读配置：静态工具没有 Spring 上下文，做不到；</li>
 *   <li><b>由启动时注入一次</b>（就是这里）。</li>
 * </ol>
 *
 * <p>代价是 {@code JdbcUrlUtil} 里有了一个静态可变字段，所以刻意做了三件事：
 * <b>只在启动时写一次</b>、<b>写入前校验字符集</b>（它会拼进 URL）、
 * <b>非法值只忽略不抛异常</b>（保护性配置不该让应用起不来）。
 */
@Configuration
public class JdbcUrlTimezoneConfig {

    public JdbcUrlTimezoneConfig(
            @Value("${datasource.mysql.server-timezone:Asia/Shanghai}") String serverTimezone) {
        JdbcUrlUtil.setServerTimezone(serverTimezone);
    }
}
