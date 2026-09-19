package com.example.sqlquery.util;

import com.example.sqlquery.exception.BusinessException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Set;

public class SqlValidateUtil {

    public SqlValidateUtil() {
    }

    public static void validate(String sql, Set<SqlType> allowedTypes) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            List<Statement> list = statements.getStatements();

            if (list == null || list.size() != 1) {
                throw new BusinessException("只允许执行单条SQL");
            }

            Statement statement = list.get(0);
            SqlType sqlType = resolveSqlType(statement);

            if (allowedTypes == null || !allowedTypes.contains(sqlType)) {
                throw new BusinessException("没有权限执行该类型SQL");
            }

            checkDangerousSql(sql);
        } catch (JSQLParserException e) {
            throw new BusinessException("SQL语法错误：" + e.getMessage());
        }
    }

    private static SqlType resolveSqlType(Statement statement) {
        if (statement instanceof Select) {
            return SqlType.SELECT;
        }
        if (statement instanceof Insert) {
            return SqlType.INSERT;
        }
        if (statement instanceof Update) {
            return SqlType.UPDATE;
        }
        if (statement instanceof Delete) {
            return SqlType.DELETE;
        }
        return SqlType.DDL;
    }

    private static void checkDangerousSql(String sql) {
        String lower = sql.toLowerCase();

        if (lower.contains("into outfile")
                || lower.contains("into dumpfile")
                || lower.contains("load_file")
                || lower.contains("sleep(")
                || lower.contains("benchmark(")) {
            throw new BusinessException("SQL包含危险操作");
        }

        if (lower.contains("information_schema")
                || lower.contains("performance_schema")
                || lower.contains("mysql.")) {
            throw new BusinessException("不允许访问系统库");
        }
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
