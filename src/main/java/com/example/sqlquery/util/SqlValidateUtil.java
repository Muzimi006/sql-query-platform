package com.example.sqlquery.util;

import com.example.sqlquery.exception.BusinessException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Set;

/**
 * SQL 校验入口。<b>全项目只有这一个入口</b> —— 查询链路和导出链路共用它。
 *
 * <p>校验分三步，顺序不能换：
 * <ol>
 *   <li>解析 + 必须恰好一条语句（拦多语句注入）</li>
 *   <li>判定语句类型，比对调用方传入的白名单（<b>默认拒绝</b>）</li>
 *   <li>危险结构与系统库拦截</li>
 * </ol>
 *
 * <p>注意：第 3 步是<b>对原始字符串做 contains</b>，可以被注释拆分绕过，
 * 详见 README「已知限制」。真正的兜底是数据库侧只读账号。
 */
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

    /**
     * 语句类型判定。<b>兜底返回 DDL</b> —— 白名单是默认拒绝的，
     * 认不出来的语句落到 DDL 上会被拒绝，这是有意的失败方向。
     */
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
}
