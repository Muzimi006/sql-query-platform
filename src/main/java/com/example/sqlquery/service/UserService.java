package com.example.sqlquery.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sqlquery.entity.User;
import com.example.sqlquery.vo.UserVO;

public interface UserService extends IService<User> {

    UserVO register(String username, String password, String nickname);

    UserVO login(String username, String password);

    User getByUsername(String username);

    void updatePassword(Long id, String oldPassword, String newPassword);
}
