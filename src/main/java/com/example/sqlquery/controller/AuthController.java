package com.example.sqlquery.controller;

import com.example.sqlquery.common.Result;
import com.example.sqlquery.dto.LoginDTO;
import com.example.sqlquery.dto.RegisterDTO;
import com.example.sqlquery.service.TokenBlacklistService;
import com.example.sqlquery.vo.LoginVO;
import com.example.sqlquery.service.UserService;
import com.example.sqlquery.util.JwtUtil;
import com.example.sqlquery.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(JwtUtil jwtUtil, UserService userService, TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto){
        UserVO registered = userService.register(
                dto.getUsername(),
                dto.getPassword(),
                dto.getNickname()
        );
        return Result.success(registered);
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto){
        UserVO loggedIn = userService.login(dto.getUsername(), dto.getPassword());

        String token = jwtUtil.generateToken(loggedIn.getId(),loggedIn.getUsername());

        LoginVO response = new LoginVO(token,loggedIn);

        return Result.success(response);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            tokenBlacklistService.add(token, Duration.ofDays(7));
        }
        return Result.success();
    }

}
