package com.example.sqlquery.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class QueryVO {

    private List<String> columns;

    private List<Map<String, Object>> rows;

    private Long costMs;

    private Boolean truncated;

}
