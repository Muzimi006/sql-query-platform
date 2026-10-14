package com.example.sqlquery.infra;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充处理器。
 *
 * <p>它是 <b>MyBatis-Plus 的一个回调实现</b>，不是配置类 —— 所以用
 * {@link Component} 而不是 {@code @Configuration}。用 {@code @Configuration}
 * 也能被扫描成 bean（功能上碰巧一样），但语义是错的：
 * {@code @Configuration} 表示"这个类负责定义 bean"，会给它做 CGLIB 代理，
 * 而这里既没有 {@code @Bean} 方法也不需要代理。
 *
 * <p>注意它<b>只负责算值</b>，真正把它装进 SqlSessionFactory 的是
 * {@link MybatisPlusConfig#sqlSessionFactory} 里的 {@code GlobalConfig} ——
 * 只把它标成 bean 是不够的，这正是之前 created_at 全为 NULL 的原因。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
