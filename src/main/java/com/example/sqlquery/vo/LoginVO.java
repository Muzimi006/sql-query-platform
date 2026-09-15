package com.example.sqlquery.vo;

import lombok.Data;

@Data
public class LoginVO {

    private String token;

    private UserVO user;

    public LoginVO(String token, UserVO user) {
        this.token = token;
        this.user = user;
    }
}
