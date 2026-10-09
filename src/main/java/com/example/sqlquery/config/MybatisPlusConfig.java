package com.example.sqlquery.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 的手工装配。
 *
 * <h2>为什么必须手工装配，以及为什么必须装配完整</h2>
 *
 * <p>在 Spring Boot 4.1 下，MyBatis-Plus 3.5.6 的自动配置
 * （{@code MybatisPlusAutoConfiguration}）会退让，有两条各自独立的原因：
 *
 * <ol>
 *   <li><b>自动配置的排序约束失效</b>：该类上的 {@code @AutoConfigureAfter} 硬引用了
 *       {@code org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration}，
 *       而 Boot 4.1 已把 DataSourceAutoConfiguration 移到了
 *       {@code org.springframework.boot.jdbc.autoconfigure}（新路径在 spring-boot-jdbc 模块里）。
 *       老路径在 classpath 上不存在 → 排序失效 → 该类上另一个类级条件
 *       {@code @ConditionalOnSingleCandidate(DataSource.class)} 有可能在 DataSource
 *       还没注册时就求值，整个自动配置退让。</li>
 *   <li><b>即便它跑了也会退让</b>：它的 {@code sqlSessionFactory} bean 方法带
 *       {@code @ConditionalOnMissingBean}，而本类显式定义了一个 SqlSessionFactory
 *       —— 用户配置优先于自动配置，自动配置直接让位。</li>
 * </ol>
 *
 * <p><b>关键点：让位不等于什么都没发生。</b>自动配置原本负责把
 * {@code Interceptor[]}（分页插件）和 {@code MetaObjectHandler}（自动填充）
 * 装进 SqlSessionFactory。手工建工厂时如果不把这两样补上，它们就是静默失效的
 * —— 不报错、不告警，只是分页悄悄查了全表、时间字段悄悄写了 NULL。</p>
 *
 * <p>已核实：{@code mybatis-plus-spring-boot-autoconfigure} 的 3.5.6 / 3.5.7 / 3.5.15
 * 三个版本引用的都还是老路径，<b>升级版本解决不了</b>。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件。
     *
     * <p>没有它时：MyBatis-Plus 的 {@code page()} 不会往 SQL 里拼 LIMIT，
     * {@code total} 也不会回填 —— 所有分页接口实际是全表查询，
     * 但接口仍然返回 200，属于静默失效。
     *
     * <p>{@link DbType#MYSQL} 指的是<b>平台自身元数据库</b>的方言
     * （数据源里配的 type 是用户业务库的方言，两者互不相干），
     * 所以它固定为 MYSQL，不跟随业务数据源的 type 变化。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 手工装配 SqlSessionFactory。
     *
     * <p>{@code setPlugins} 和 {@code setGlobalConfig} 这两行是<b>必需的</b>，
     * 少任何一行就会退回本类 javadoc 里描述的那两个静默 bug。
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               MybatisPlusInterceptor mybatisPlusInterceptor,
                                               MyMetaObjectHandler myMetaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        // ① 分页插件：不设则分页失效（全表查询 + total 恒为 0）
        factoryBean.setPlugins(mybatisPlusInterceptor);

        // ② 自动填充：不设则 @TableField(fill = ...) 的字段被显式写入 NULL，
        //    连数据库的 DEFAULT CURRENT_TIMESTAMP 也会被顶掉
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(myMetaObjectHandler);
        factoryBean.setGlobalConfig(globalConfig);

        return factoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
