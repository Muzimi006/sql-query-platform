# 项目状态交接文档

> 新会话请先读本文件 + `TODO.md` + 项目源码，再继续。

## 项目简介

- 项目名：SQL 查询与审核平台（Mini Archery）
- 路径：`D:\sql-query-platform\sql-query-platform`
- 技术栈：Spring Boot 4.1.0、Java 17、MyBatis-Plus、MySQL、JWT、BCrypt
- 当前定位：Java 后端学习项目，用于找后端实习

## 已完成的模块

- 用户注册 / 登录
- BCrypt 密码加密
- JWT 登录认证 + 拦截器
- 全局异常处理
- DTO 参数校验
- VO 返回脱敏
- 数据源 CRUD
- 数据源测试连接（JDBC）
- SQL 工作台（执行 SQL + 返回结果 + 保存查询历史）

## 当前项目结构

```text
com.example.sqlquery
├── common       // Result 统一返回
├── config       // JwtInterceptor、MybatisPlusConfig、PasswordConfig、WebMvcConfig
├── controller   // AuthController、DbSourceController、QueryController
├── dto          // RegisterDTO、LoginDTO、DbSourceDTO、QueryDTO
├── entity       // User、DbSource、QueryHistory
├── exception    // BusinessException、GlobalExceptionHandler
├── mapper       // UserMapper、DbSourceMapper、QueryHistoryMapper
├── service      // 接口
│   └── impl     // 实现类
├── util         // JwtUtil、JdbcUrlUtil
└── vo           // UserVO、LoginVO、DbSourceVO、QueryVO
```

## 重要说明

- 数据源密码目前仍是明文存储，TODO 中已记录，后续要做加密
- JWT 密钥目前写死在代码里，TODO 中已记录，后续要配置化
- 项目使用 Spring Boot 4 + MyBatis-Plus，需要 `MybatisPlusConfig` 手动配置 SqlSessionFactory
- 所有接口除注册/登录外都需要 `Authorization: Bearer <token>`

## 下一步计划

按 TODO.md 推进，优先做：

1. SQL 安全校验（只允许 SELECT）
2. 查询历史列表接口
3. 收藏 SQL
4. 异步导出
5. 数据源密码加密
6. 部署

## 待办

详见 `TODO.md`。
