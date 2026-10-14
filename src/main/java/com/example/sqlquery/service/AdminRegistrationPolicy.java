package com.example.sqlquery.service;

import com.example.sqlquery.domain.UserRole;
import com.example.sqlquery.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 决定一次注册申请应该拿到什么角色。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>原来的实现是 {@code user.setRole("USER")} 写死 —— 结果就是
 * <b>系统里永远不存在管理员</b>：角色白名单里配了 ADMIN，却没有任何路径能产生一个 ADMIN 账号。
 * 一个"配了但到不了"的分支比没有这个分支更危险，因为它会让人以为权限体系已经落地了。
 *
 * <h2>为什么用"邀请码"而不是别的做法</h2>
 *
 * <p>几种常见做法和它们的问题：
 * <table border="1">
 *   <tr><th>做法</th><th>问题</th></tr>
 *   <tr><td>把某个用户名写进配置，注册时匹配就给管理员</td>
 *       <td>❌ <b>可以被抢注</b>：配置里写着 {@code admin}，只要没人先注册，
 *           任何人都能注册这个名字直接拿到管理员权限</td></tr>
 *   <tr><td>第一个注册的用户自动成为管理员</td>
 *       <td>❌ 同样是抢注，而且在已上线的环境里完全不可控</td></tr>
 *   <tr><td>手工改数据库</td>
 *       <td>⚠️ 安全，但没有"路径"，回到"没有创建管理员的方式"</td></tr>
 *   <tr><td><b>注册时提交邀请码</b>（本实现）</td>
 *       <td>✅ 邀请码是秘密，抢注用户名没有用；不配置就是关闭自助创建</td></tr>
 * </table>
 *
 * <h2>默认关闭</h2>
 *
 * <p>{@code platform.admin-register-code} <b>不配置</b>时，任何人都注册不出管理员账号。
 * 这是一个安全的默认值：忘记配置的后果是"暂时建不出管理员"，
 * 而不是"任何人都能把自己变成管理员"。
 */
@Slf4j
@Component
public class AdminRegistrationPolicy {

    /**
     * 管理员邀请码。
     *
     * <p>⚠️ 只从环境变量注入，不写进配置文件、更不进仓库 —— 它是一个凭据。
     * 空值表示关闭自助创建管理员。
     */
    @Value("${platform.admin-register-code:}")
    private String adminRegisterCode;

    /**
     * 判定这次注册应该赋什么角色。
     *
     * @param submittedCode 客户端提交的邀请码，可以为 {@code null}（普通注册）
     * @return {@link UserRole#ADMIN} 或 {@link UserRole#USER}
     * @throws BusinessException 提交了邀请码但不正确、或者环境根本没开放管理员注册
     */
    public String resolveRole(String submittedCode) {
        boolean codeRequired = StringUtils.hasText(adminRegisterCode);
        boolean codeSubmitted = StringUtils.hasText(submittedCode);

        if (!codeRequired) {
            if (codeSubmitted) {
                // 环境没开放，但有人提交了邀请码：很可能是照着文档试的。
                // 明确告诉他不开放，比悄悄给他一个 USER 账号更好 —— 否则他会以为成功提权了。
                throw new BusinessException("当前环境未开放管理员注册");
            }
            return UserRole.USER;
        }

        if (!codeSubmitted) {
            return UserRole.USER;
        }

        if (!constantTimeEquals(adminRegisterCode, submittedCode)) {
            log.warn("注册时提交了错误的管理员邀请码");
            throw new BusinessException("管理员邀请码不正确");
        }

        log.info("通过邀请码创建管理员账号");
        return UserRole.ADMIN;
    }

    /**
     * 恒定时间比较。
     *
     * <p>用 {@code equals} 比较凭据时，比较过程会在第一个不同的字符处提前返回，
     * 攻击者可以通过测量响应时间逐字节猜出正确值。{@link MessageDigest#isEqual} 不会提前返回。
     *
     * <p>诚实说明它的边界：<b>长度差异仍然会被提前发现</b>（长度不同直接返回 false），
     * 所以它防的是"逐字节猜测内容"，不是"猜出长度"。对邀请码这个场景足够了。
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
