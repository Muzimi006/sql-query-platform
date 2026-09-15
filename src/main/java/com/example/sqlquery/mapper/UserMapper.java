package com.example.sqlquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sqlquery.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
