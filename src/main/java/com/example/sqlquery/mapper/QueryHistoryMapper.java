package com.example.sqlquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sqlquery.entity.QueryHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QueryHistoryMapper extends BaseMapper<QueryHistory> {
}
