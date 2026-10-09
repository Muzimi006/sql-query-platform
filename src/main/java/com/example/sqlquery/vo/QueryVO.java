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

    /**
     * 非查询语句（INSERT / UPDATE / DELETE）影响的行数。
     *
     * <p>查询语句为 {@code null}。区分"返回了 0 行"和"这条语句本来就不返回结果集"
     * 是必要的：前者 `rows` 是空列表，后者 `rows` 与 `columns` 都为空且这里非空。
     */
    private Integer affectedRows;

}
