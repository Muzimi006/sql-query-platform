package com.example.sqlquery.util;

import com.example.sqlquery.exception.BusinessException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

public class SqlValidateUtil {

    public SqlValidateUtil() {
    }

    public static void validateSelect(String sql){
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if(!(statement instanceof Select)){
                throw new BusinessException("只允许执行SELECT查询");
            }
        }catch (JSQLParserException e){
            throw new BusinessException("SQL语法错误:"+e.getMessage());
        }
    }
}
