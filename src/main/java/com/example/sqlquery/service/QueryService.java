package com.example.sqlquery.service;

import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.vo.QueryVO;

public interface QueryService {

    QueryVO execute(Long userId, QueryDTO dto);
}
