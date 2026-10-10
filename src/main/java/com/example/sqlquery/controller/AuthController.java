package com.example.sqlquery.controller;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.sqlquery.common.Result;
import com.example.sqlquery.dto.LoginDTO;
import com.example.sqlquery.dto.RegisterDTO;
import com.example.sqlquery.service.TokenBlacklistService;
import com.example.sqlquery.vo.LoginVO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.UserService;
import com.example.sqlquery.util.ClientIpUtil;
import com.example.sqlquery.util.JwtUtil;
import com.example.sqlquery.util.RateLimitUtil;
import com.example.sqlquery.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "注册 / 登录 / 登出 —— 全项目仅有的三个不需要 token 的接口")
/*
 * ⚠️ 这三个方法上的 @SecurityRequirements（空）不能删，也不能换成 @Operation(security = {})。
 *
 * OpenApiConfig 里声明了**全局**的 Bearer 要求，所以默认所有接口在文档里都"要 token"。
 * 公开接口必须逐个覆盖成空 —— 但 **@Operation(security = {}) 在 springdoc 3.1.1 下是无效的**：
 * 空数组被当成"没设置"，生成的 operation 上根本没有 security 字段，于是全局要求照旧生效，
 * 文档上看起来注册/登录也要 token，调用方会白折腾。
 * 必须用独立的 @SecurityRequirements 注解。
 *
 * 这一条是靠 OpenApiContractIntegrationTest 抓出来的 ——
 * 我先按直觉写了 security = {}，测试断言"恰好这三个接口声明了 security: []"，
 * 直接变红并报出实际是空集合。
 */
public class AuthController {

    /**
     * 登录限流的两个维度。
     *
     * <p>为什么要两个而不是只按 IP：
     * <ul>
     *   <li><b>只按 IP</b>：攻击者换一个出口 IP 就能绕过（用代理池成本很低）；
     *       而且同一个校园网 / 公司出口下的正常用户会共用同一个桶，
     *       阈值定小了会误伤自己人。</li>
     *   <li><b>只按账号</b>：攻击者可以拿一个字典去撞<b>很多不同的账号</b>，
     *       每个账号都只错几次，单账号维度看不出异常。</li>
     * </ul>
     * 两个维度<b>同时</b>生效才能覆盖这两种模式。
     *
     * <p>按账号限流的已知代价：知道用户名的人可以故意输错密码把该账号
     * 锁住 1 分钟（对目标账号的拒绝服务）。这里的取舍是窗口取短（60 秒）、
     * 阈值取宽（10 次），把影响压到可接受范围；彻底解决需要引入验证码或
     * 失败次数递增退避，属于下一步的事。
     */
    private static final int LOGIN_LIMIT_PER_IP = 20;
    private static final int LOGIN_LIMIT_PER_USERNAME = 10;

    /** 注册的限流窗口：注册接口不限流的话，可以被脚本批量刷出大量垃圾账号。 */
    private static final int REGISTER_LIMIT_PER_IP = 5;

    private static final int AUTH_WINDOW_SECONDS = 60;

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RateLimitUtil rateLimitUtil;

    public AuthController(JwtUtil jwtUtil, UserService userService,
                          TokenBlacklistService tokenBlacklistService,
                          RateLimitUtil rateLimitUtil) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.rateLimitUtil = rateLimitUtil;
    }

    @Operation(summary = "注册",
            description = "**不需要 token**。带 adminCode 才拿到 ADMIN 角色"
                    + "（platform.admin-register-code，留空即关闭自助创建管理员）。按 IP 限流 5 次 / 60 秒。")
    @SecurityRequirements
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        // 注册接口原来完全没有限流，一个脚本就能批量刷出垃圾账号
        String ip = ClientIpUtil.getClientIp(request);
        if (!rateLimitUtil.tryAcquireSlidingWindow(
                "rate:register:ip:" + ip, REGISTER_LIMIT_PER_IP, AUTH_WINDOW_SECONDS)) {
            throw new BusinessException("注册过于频繁，请稍后再试");
        }

        UserVO registered = userService.register(
                dto.getUsername(),
                dto.getPassword(),
                dto.getNickname(),
                dto.getAdminCode()
        );
        return Result.success(registered);
    }

    /**
     * 登录。
     *
     * <p>限流必须放在<b>校验密码之前</b>：放在后面等于每次都真的去查库、做一次
     * BCrypt 比对（这是有意设计成很慢的操作），攻击者用很小的成本就能把 CPU 打满。
     * 限流的意义之一就是不让请求走到那一步。
     */
    @Operation(summary = "登录",
            description = "**不需要 token**。返回 LoginVO{token, user}。"
                    + "⚠️ 角色在 **user.role** 里，不是顶层字段。"
                    + "限流是 **IP + 账号双维度**（20 次 / 10 次，60 秒窗口）："
                    + "只按 IP 挡不住代理池，只按账号挡不住撞库。")
    @SecurityRequirements
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        String ip = ClientIpUtil.getClientIp(request);

        boolean ipAllowed = rateLimitUtil.tryAcquireSlidingWindow(
                "rate:login:ip:" + ip, LOGIN_LIMIT_PER_IP, AUTH_WINDOW_SECONDS);
        boolean accountAllowed = rateLimitUtil.tryAcquireSlidingWindow(
                "rate:login:username:" + dto.getUsername(), LOGIN_LIMIT_PER_USERNAME, AUTH_WINDOW_SECONDS);

        if (!ipAllowed || !accountAllowed) {
            throw new BusinessException("登录尝试过于频繁，请稍后再试");
        }

        UserVO loggedIn = userService.login(dto.getUsername(), dto.getPassword());

        String token = jwtUtil.generateToken(loggedIn.getId(), loggedIn.getUsername(), loggedIn.getRole());

        LoginVO response = new LoginVO(token,loggedIn);

        return Result.success(response);
    }

    /**
     * 登出：把当前 token 加入黑名单，使其在自然过期之前失效。
     *
     * <p>这个接口在 {@code WebMvcConfig} 的拦截器白名单里 —— 这是<b>有意的</b>：
     * token 已经过期的用户也应该能点"退出"（否则前端会卡在"看起来还登录着"的状态）。
     * 代价是这里拿到的 token <b>完全没验过签</b>，所以方法内部必须自己验一次。
     *
     * <p>不验签会怎样：任何人都能 POST 一个随手编的字符串，
     * 每请求一次就在 Redis 里写下一条存活到 TTL 结束的键 ——
     * 一个不需要登录、可以被无限放大的写入入口。
     */
    @Operation(summary = "登出",
            description = "**不需要 token**（刻意的：token 已过期的用户也该点得动退出）。"
                    + "代价是这里拿到的 token 完全没验签，所以**方法内部自己验一次**，验不过就正常返回。"
                    + "不这么做的话，任何人 POST 一个乱编的字符串就能往 Redis 里灌黑名单键 ——"
                    + "一个不需要登录、可被无限放大的写入入口。")
    @SecurityRequirements
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // 没带 token 也返回成功：客户端本来就要清本地状态，没必要为这个报错
            return Result.success();
        }

        String token = authHeader.substring(7);

        DecodedJWT jwt;
        try {
            jwt = jwtUtil.verifyToken(token);
        } catch (JWTVerificationException e) {
            // 签名不对或已过期 —— 本来就不是一个有效凭据，没有拉黑的必要。
            // 注意这里必须"正常返回"而不是抛 401：用户点退出就应该退得掉
            return Result.success();
        }

        // TTL 取这个 token 自己的剩余有效期，而不是写死的天数：
        // 黑名单只需要覆盖"它还有可能被拿去用"的那段时间，
        // 写死 7 天会在 Redis 里留下一批永远不会被再查的垃圾键。
        tokenBlacklistService.add(token, jwtUtil.getRemainingValidity(jwt));

        return Result.success();
    }

}
