package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sqlquery.entity.User;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.UserMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.AdminRegistrationPolicy;
import com.example.sqlquery.service.UserService;
import com.example.sqlquery.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper,User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final AdminRegistrationPolicy adminRegistrationPolicy;
    private final SqlPlatformMetrics metrics;

    /**
     * 一条"永远匹配不上"的哈希，只用来给"用户不存在"这条路径**凑出同样的耗时**。
     *
     * <p>懒生成而不是启动时生成：哈希必须用**当前配置的**成本因子（否则时间对不齐），
     * 所以只能由注入的 encoder 生成；而它只在"真的有人用不存在的用户名登录"时才用得上，
     * 放在启动时等于让每次启动都白付一次 BCrypt。
     *
     * <p>{@code volatile}：多个登录线程可能同时第一次用到它，重复生成一次无害，
     * 但不能让别的线程读到半初始化的值。
     */
    private volatile String dummyHash;

    public UserServiceImpl(PasswordEncoder passwordEncoder,
                           AdminRegistrationPolicy adminRegistrationPolicy,
                           SqlPlatformMetrics metrics) {
        this.passwordEncoder = passwordEncoder;
        this.adminRegistrationPolicy = adminRegistrationPolicy;
        this.metrics = metrics;
    }

    @Override
    public UserVO register(String username, String password, String nickname, String adminCode) {
        User exist = getByUsername(username);
        if(exist!=null){
            throw new BusinessException("用户名已存在");
        }

        User user=new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        // 角色由策略决定，不在这里写死 —— 原来固定成 USER 意味着
        // 权限配置里的 ADMIN 分支永远到不了，等于没有管理员这个角色
        user.setRole(adminRegistrationPolicy.resolveRole(adminCode));
        user.setStatus(1);
        save(user);
        return toVO(user);
    }

    @Override
    public UserVO login(String username, String password) {
        User user = getByUsername(username);
        if (user == null) {
            // ⚠️ 用户不存在时**也要**做一次哈希比对，只为消耗掉同样的时间（见 dummyHash()）。
            //    原来的写法是 `user == null || !matches(...)` —— `||` 会短路，
            //    于是这条路径不做 BCrypt，实测 17ms vs 100ms：
            //    **统一错误文案挡住了"按文案枚举"，挡不住"按响应耗时枚举"**。
            passwordEncoder.matches(password, dummyHash());
            throw new BusinessException("用户名不存在或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名不存在或密码错误");
        }

        // ⚠️ 密码对了不等于能进来：user.status = 0 表示账号已被停用（列默认 1）。
        //    以前这里**没有**这一步 —— 后果是「停用一个账号」在代码上毫无作用：
        //    运维把 status 改成 0 之后，本人照样登录、照样拿到 token。
        //    只认「明确等于 0」：status 为 NULL 的历史行按启用处理，避免一次上线把老账号锁在门外。

        if (Integer.valueOf(0).equals(user.getStatus())) {
            throw new BusinessException("该账号已被停用，请联系管理员");
        }

        // ⭐ 密码校验成功，是**唯一**能拿到明文的时刻 —— 也只有在此时才谈得上重算哈希。
        //    背景：BCrypt 的成本因子写在每条哈希串里，matches() 读的是哈希串里的那个值，
        //    所以把 platform.password.bcrypt-strength 调高之后，**老哈希不会自动升级** ——
        //    没有这一步，调高成本因子只会对**新注册/改密码**的用户生效，而库里那些老账号
        //    会一直停在旧强度上，且没有任何地方看得出来。
        rehashIfOutdated(user, password);

        return toVO(user);
    }

    /**
     * 若这条哈希用的成本因子低于当前配置，就顺手重算一次（渐进升级）。
     *
     * <h2>⚠️ 为什么失败**不能**影响登录</h2>
     *
     * <p>用户已经通过了校验 —— 密码是对的。写库失败（平台库抖一下）不该让一次**成功的登录**
     * 变成"用户名不存在或密码错误"，那会让用户反复重试、甚至去改密码。
     * 所以这里只记 WARN + 计数器（`password.rehash`），不动登录结果。
     *
     * <p>与"查询历史写失败不影响查询结论"是同一条原则：**附属动作的失败不改变主结论**。
     */
    private void rehashIfOutdated(User user, String rawPassword) {
        String stored = user.getPassword();
        if (stored == null || !passwordEncoder.upgradeEncoding(stored)) {
            return;
        }
        try {
            String upgraded = passwordEncoder.encode(rawPassword);
            // 条件更新：只改密码列，且要求密码列仍是刚校验过的那一条 ——
            // 避免把用户在别处刚改过的密码又覆盖回去（updateById 会整行覆盖，这里刻意不用）。
            int affected = getBaseMapper().update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, user.getId())
                    .eq(User::getPassword, stored)
                    .set(User::getPassword, upgraded));
            if (affected == 1) {
                metrics.recordPasswordRehashed();
                log.info("登录成功，已把密码哈希升级到当前成本因子。userId={}", user.getId());
            } else {
                // 影响 0 行：这条记录在此期间被改过（例如用户刚改完密码）—— 以库里的为准，不覆盖
                log.info("密码哈希升级被跳过：哈希在校验之后已被改写。userId={}", user.getId());
            }
        } catch (Exception e) {
            metrics.recordPasswordRehashFailed();
            log.warn("密码哈希升级失败（**登录本身不受影响**）。userId={}", user.getId(), e);
        }
    }

    /**
     * 取一条"永远匹配不上"的哈希，用来把"用户不存在"这条路径的耗时对齐到"密码错"那条。
     *
     * <h2>为什么需要它</h2>
     *
     * <p>登录的错误文案是统一的（「用户名不存在或密码错误」），但**耗时没有统一**：
     * 用户不存在时若直接短路，就不做 BCrypt，两条路径差一个数量级
     * （本机实测：不存在 ≈ 17ms，密码错 ≈ 100ms，成本因子 10）。
     * 拿一份用户名字典打一遍、按响应时间分类，就能枚举出哪些用户名存在 ——
     * 这是"信息泄露"，不是"能在库里干坏事"，但它省掉了攻击者的试错成本。
     *
     * <p>业界做法一致：Spring Security 的 {@code DaoAuthenticationProvider} 就有
     * {@code mitigateAgainstTimingAttack} 与"用户不存在时也拿假哈希比一次"的实现。
     *
     * <h2>⚠️ 它不是防线的主体（必须说清楚）</h2>
     *
     * <p>真正限制"每秒能做多少次 BCrypt"的是**登录限流**，而且它在 BCrypt 之前：
     * IP 20 次 + 账号 10 次 / 60 秒（见 {@code AuthController}）。
     * 这里补的是**信息泄露的那一半**，代价是"不存在的用户名也要付一次哈希"——
     * 这是刻意的：拿一次哈希换掉一条枚举通道，而且它本来就在限流的预算之内。
     *
     * @return BCrypt 哈希串；同一次进程内复用同一条（第一次调用时生成）
     */
    private String dummyHash() {
        String cached = dummyHash;
        if (cached == null) {
            cached = passwordEncoder.encode("timing-equalizer-placeholder");
            dummyHash = cached;
        }
        return cached;
    }

    @Override
    public User getByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        return getOne(wrapper);
    }

    @Override
    public void updatePassword(Long id, String oldPassword, String newPassword) {
        User user = getById(id);
        if(user == null || !passwordEncoder.matches(oldPassword, user.getPassword())){
            throw new BusinessException("用户名不存在或密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        updateById(user);
    }

    private UserVO toVO(User user){
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        return vo;
    }
}
