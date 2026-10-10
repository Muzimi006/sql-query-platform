package com.example.sqlquery.exception;

/**
 * 请求在<b>碰到任何资源之前</b>就被拒了 —— 并发闸门、熔断器这类"保护性拒绝"。

 * <h2>为什么要单独一个类型</h2>

 * <p>这条链路上原来有两个洞是同一个形状：
 *
 * <ol>
 *   <li><b>审计日志白写一条</b>：{@code AuditLogAspect} 是 {@code @Before}，
 *       跑在控制器方法之前，所以"被闸门拒绝"的请求也会往<b>主库</b>插一条 audit_log。
 *       压测实测的后果是负向扩展：并发 8 → 64 时成功吞吐 296.5 → 87.5 QPS、错误率 14% → 92% ——
 *       <b>闸门保护了业务库，代价却记在主库上</b>，而主库的连接池同样只有 10 条。</li>
 *   <li>日志与审计里看不出"这次什么都没做"：被拒和"执行失败"写成同一种记录，
 *       事后复盘时会把保护性拒绝当成故障。</li>
 * </ol>

 * <p>所以这里用一个**独立的类型**表达它，让上层能区分三件事：
 * <b>没做事就被拒</b>（本类）、<b>做了事但失败</b>（普通 BusinessException）、<b>成功了</b>。

 * <p>⚠️ 它继承 {@link BusinessException}，所以：
 *
 * <ul>
 *   <li>对用户的表现完全不变（{@code GlobalExceptionHandler} 仍然按 BusinessException 处理，
 *       仍然是 HTTP 200 + 业务 code + 一句人话）；</li>
 *   <li>但它多了一个**类型**，于是审计切面可以据此不写库；</li>
 *   <li>它也**不能**被当成"基础设施失败"** —— 熔断器只沿 cause 链认连接类异常，
 *       拒绝本身不带 cause，所以不会把自己拒掉的请求算成数据源故障（否则会自己把自己熔断）。</li>
 * </ul>
 */
public class RejectedBeforeWorkException extends BusinessException {

    public RejectedBeforeWorkException(String message) {
        super(message);
    }
}
