package com.example.sqlquery.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sqlquery.entity.User;
import com.example.sqlquery.vo.UserVO;

public interface UserService extends IService<User> {

    /**
     * 注册。
     *
     * @param adminCode 可选的管理员邀请码；为 {@code null} 时按普通用户注册。
     *                  角色判定不在这里做，交给 {@link AdminRegistrationPolicy} ——
     *                  这样"什么条件下能成为管理员"这条规则只有一个实现处。
     */
    UserVO register(String username, String password, String nickname, String adminCode);

    UserVO login(String username, String password);

    User getByUsername(String username);

    void updatePassword(Long id, String oldPassword, String newPassword);
}
