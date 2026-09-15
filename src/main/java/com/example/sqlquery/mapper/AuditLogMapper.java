package com.example.sqlquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sqlquery.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
