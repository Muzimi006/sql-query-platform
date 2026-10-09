package com.example.sqlquery.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 的取值入口。
 *
 * <p><b>只信 {@code remoteAddr}，不信 {@code X-Forwarded-For}。</b>
 * 这个选择需要解释，因为它和"很多示例代码"的做法相反：
 *
 * <p>{@code X-Forwarded-For} 是<b>客户端可以随意伪造</b>的普通请求头。
 * 一旦拿它当限流的分桶键，攻击者每个请求换一个编造的 IP，限流就完全失效 ——
 * 一个为了"支持反向代理"而引入的便利，直接把防护变成了摆设。
 *
 * <p>部署在 Nginx / 网关后面时的正确做法是：开启
 * {@code server.forward-headers-strategy=framework}，
 * 由容器根据可信代理链把 {@code remoteAddr} 改写成真实客户端地址。
 * 这样这里不需要改代码，也不需要信任任何来路不明的头。
 */
public final class ClientIpUtil {

    private ClientIpUtil() {
    }

    /** 取客户端 IP；取不到时返回 {@code "unknown"}，保证调用方永远有一个可用的分桶键。 */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr == null || remoteAddr.isEmpty()) ? "unknown" : remoteAddr;
    }
}
