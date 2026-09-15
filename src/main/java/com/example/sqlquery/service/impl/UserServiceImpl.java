package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sqlquery.entity.User;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.UserMapper;
import com.example.sqlquery.service.UserService;
import com.example.sqlquery.vo.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper,User> implements UserService {

    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserVO register(String username, String password, String nickname) {
        User exist = getByUsername(username);
        if(exist!=null){
            throw new BusinessException("用户名已存在");
        }

        User user=new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setRole("USER");
        user.setStatus(1);
        save(user);
        return toVO(user);
    }

    @Override
    public UserVO login(String username, String password) {
        User user = getByUsername(username);
        if (user == null || !passwordEncoder.matches(password,user.getPassword())) {
            throw new BusinessException("用户名不存在或密码错误");
        }
        return toVO(user);
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
