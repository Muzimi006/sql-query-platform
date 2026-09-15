package com.example.sqlquery.service;

import java.time.Duration;

public interface TokenBlacklistService {

    void add(String token, Duration expire);

    boolean contains(String token);
}
