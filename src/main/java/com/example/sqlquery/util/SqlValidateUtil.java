package com.example.sqlquery.util;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.exception.SqlTypeNotAllowedException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 校验入口。<b>全项目只有这一个入口</b> —— 查询链路和导出链路共用它。
 *
 * <h2>校验分四步，顺序不能换</h2>
 * <ol>
 *   <li><b>解析 + 必须恰好一条语句</b>（拦多语句注入）。解析失败也算拦下 —— 失败方向是安全的。</li>
 *   <li><b>判定语句类型，比对调用方传入的白名单</b>（默认拒绝）。</li>
 *   <li><b>基于 AST 检查危险函数与系统库</b>。</li>
 *   <li><b>两个字符串兜底检查</b>：MySQL 可执行注释、{@code INTO OUTFILE}。见下面的说明。</li>
 * </ol>
 *
 * <h2>为什么第 3 步从「字符串包含」改成了 AST</h2>
 *
 * <p>旧实现是对原始 SQL 做 {@code contains} 判断，有两个方向的问题，
 * 而且<b>两个都实测复现过</b>：
 *
 * <table border="1">
 *   <caption>字符串匹配的漏洞与误伤</caption>
 *   <tr><th>SQL</th><th>旧实现</th><th>为什么</th></tr>
 *   <tr><td>{@code select sleep/**&#47;(1)}</td><td>🔴 放行</td>
 *       <td>实测在 MySQL 上执行了 1057ms —— 注释把 {@code sleep(} 拆开了，
 *           而 {@code contains("sleep(")} 匹配不到</td></tr>
 *   <tr><td>{@code select SLeeP (1)}</td><td>🔴 放行</td>
 *       <td>函数名和括号之间多一个空格，{@code "sleep("} 就匹配不到</td></tr>
 *   <tr><td>{@code select count(*) from \`mysql\`.\`user\`}</td><td>🔴 放行</td>
 *       <td>检查的是 {@code "mysql."}，而反引号把点和库名隔开了（实测该语句能查到用户表）</td></tr>
 *   <tr><td>{@code select 'sleep(1)' as note}</td><td>🔴 误杀</td>
 *       <td>字符串字面量里出现这几个字符就被拒绝 —— 一句完全合法的查询</td></tr>
 * </table>
 *
 * <p>换成 AST 之后，判断依据是"<b>这里是不是一次函数调用 / 一张表</b>"，
 * 而不是"<b>文本里有没有这几个字符</b>"：上面的四条全部被正确处理。
 *
 * <h2>为什么 AST 之外还要保留两个字符串检查</h2>
 *
 * <p>因为<b>解析器看不见的东西，AST 自然也检查不到</b>：
 *
 * <ul>
 *   <li><b>MySQL 可执行注释 {@code /*! ... *&#47;}</b>：MySQL 会把里面的内容
 *       当作真正的 SQL 执行，而 JSqlParser 把它当普通注释<b>直接丢掉</b>。
 *       实测：{@code select /*!50000 sleep(1)*&#47; as x} 在 MySQL 上耗时 1096ms，
 *       说明注释里的 SLEEP 确实执行了，而解析出来的 AST 里干干净净。
 *       所以这一条<b>只能</b>对原始字符串判断。</li>
 *   <li><b>{@code INTO OUTFILE} / {@code INTO DUMPFILE}</b>：JSqlParser 4.9 解析不了它，
 *       会先以「语法错误」被拦下。但"拦得住"是解析器的偶然行为，不是我们的规则 ——
 *       哪天一升级它认识了这个语法，防线就静默消失了。所以这里再明确拦一次。</li>
 * </ul>
 *
 * <p>这两个检查都跑在 {@link #stripStringLiterals} 之后的结果上，
 * 避免"字符串字面量里恰好含这几个字符"造成的误杀；
 * {@code INTO OUTFILE} 那一条还额外跑在 {@code statement.toString()} 上 ——
 * AST 重新序列化会<b>丢掉所有注释</b>，于是 {@code select 1 -- into outfile}
 * 这种"注释里提到关键词"的写法不会被误判。
 *
 * <h2>这套检查的边界（必须说清楚）</h2>
 *
 * <p>它挡的是"<b>手滑和顺手</b>"，不是"<b>铁了心的攻击者</b>"。
 * 真正的兜底是数据库侧只读账号：应用层无论被怎么绕过，
 * 最终能在数据库上做的事情仍然被账号权限限制着。
 */
public final class SqlValidateUtil {

    private SqlValidateUtil() {
    }

    /**
     * 危险函数（MySQL 方言）。
     *
     * <p>收录标准是"<b>对一个只读查询平台毫无意义，且能造成危害</b>"：
     * 拖慢数据库、读服务器文件、执行系统命令、抢占命名锁。
     * 正常的业务查询里出现其中任何一个，都说明这条 SQL 不该在这里跑。
     *
     * <p>⚠️ 多方言改造时，这个集合必须<b>按方言拆分</b>：
     * PostgreSQL 侧对应的是 {@code pg_sleep}、{@code pg_read_file}、{@code dblink} 等，
     * 名字完全不同，不能直接复用。
     */
    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of(
            "sleep",        // 拖慢数据库（时间盲注常用）
            "benchmark",    // 同上，重复执行表达式
            "load_file",    // 读服务器上的任意文件
            "get_lock",     // 抢占命名锁，可用来制造阻塞
            "release_lock",
            "sys_exec",     // UDF：执行系统命令
            "sys_eval"
    );

    /**
     * 系统库。
     *
     * <p>判断的是<b>表名里的库名前缀</b>，而不是文本里有没有这几个词 ——
     * 所以反引号、双引号、大小写、跨 JOIN、子查询里的写法都能覆盖到。
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "mysql",
            "information_schema",
            "performance_schema",
            "sys"
    );

    /** MySQL 可执行注释的起始标记。内容是会被真正执行的，所以直接拒绝。 */
    private static final String EXECUTABLE_COMMENT_MARKER = "/*!";

    /**
     * 校验通过之后带出来的信息。
     *
     * @param sqlType 语句类型
     * @param tables  这条 SQL 涉及的表名（AST 里解析出来的，是<b>字面表名</b>，
     *                不一定真实存在 —— 它来自用户输入，不要拿它去拼 SQL）
     */
    public record ValidationResult(SqlType sqlType, List<String> tables) {
    }

    /**
     * 校验一条 SQL 能不能执行。
     *
     * <p><b>为什么必须传方言</b>：危险函数与系统库的名单是<b>方言相关</b>的 ——
     * MySQL 是 {@code sleep} / {@code load_file}，PostgreSQL 是 {@code pg_sleep} / {@code pg_read_file}，
     * 名字一个都对不上。拿 MySQL 的名单去检查 PG 的 SQL，等于什么都没检查。
     *
     * <p>所以这里<b>不提供省略方言的重载</b>：多一个"忘了传"的口子，
     * 就等于多一条"看起来在检查、其实没检查"的路径。
     *
     * <p><b>返回值说明</b>：它顺带把"这条 SQL 是什么类型、动了哪几张表"带出来 ——
     * 这两样在遍历语法树时本来就已经拿到了。工单需要展示"会动哪几张表"，
     * 如果为了这个再解析一次，就等于对同一段 SQL 解析两遍，
     * 而且多了一处"可能和校验时看到的不一致"的地方。
     * <b>一次解析，既做校验、又出结果</b>，是这里唯一合理的做法。
     *
     * @param dialect 该 SQL 将要执行的那个数据源的方言
     * @return 语句类型 + 涉及的表名
     */
    public static ValidationResult validate(String sql, Set<SqlType> allowedTypes, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException("SQL不能为空");
        }
        if (dialect == null) {
            throw new BusinessException("无法确定该数据源的方言，拒绝执行");
        }

        // 第 1 步：解析 + 单语句
        Statement statement = parseSingleStatement(sql);

        // 第 2 步：类型白名单（默认拒绝）
        SqlType sqlType = resolveSqlType(statement);
        if (allowedTypes == null || !allowedTypes.contains(sqlType)) {
            // 抛带类型的子类而不是裸的 BusinessException：消息一字不变，
            // 但调用方（工单链路）能据此说出「这是查询，不用审批」这种正确的话。
            // 详见 SqlTypeNotAllowedException 的注释。
            throw new SqlTypeNotAllowedException(sqlType, allowedTypes);
        }

        // 第 3 步：AST 检查。
        // getTableList 会完整遍历一遍语法树（含子查询 / CTE / UNION / JOIN），
        // 一趟同时把「表名」和「函数名」都收上来，不需要走两遍。
        SqlInspector inspector = new SqlInspector();
        // ⚠️ 这里以前调的是 getTableList(statement) —— 它在 JSqlParser 4.9 里已标注 @Deprecated。
        //    查过它的字节码：方法体就是 `new ArrayList<>(getTables(statement))`，一个字不差，
        //    所以自己包一层等价、行为完全一致（顺序也一样，因为包的还是同一个 Set）。
        //    顺带说明为什么不用 getTables 直接返回的 Set：下面 checkSystemSchema 和
        //    ValidationResult（会回给前端当 involvedTables）都按 List 用，且希望能保留遍历顺序。
        List<String> tables = new ArrayList<>(inspector.getTables(statement));
        checkDangerousFunctions(dialect, inspector.foundFunctions);
        checkSystemSchema(dialect, tables);

        // 第 4 步：解析器看不见的两个东西，只能回到字符串上判断
        checkExecutableComment(stripStringLiterals(sql, dialect));
        checkIntoOutFile(stripStringLiterals(statement.toString(), dialect));

        return new ValidationResult(sqlType, tables);
    }

    /**
     * 解析，并要求<b>恰好一条语句</b>。
     *
     * <p>解析失败抛「语法错误」而不是「危险操作」：这是两种不同的失败，
     * 给用户的信息也应当不同 —— 一个是"你写错了"，一个是"不许这么写"。
     * 两者都拒绝，只是提示不一样。
     */
    private static Statement parseSingleStatement(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new BusinessException("SQL语法错误：" + e.getMessage());
        }

        // ⚠️ getStatements() 在 4.9 里已标注 @Deprecated —— 因为 Statements 自己就
        //    `extends ArrayList<Statement>`（反编译确认过），所以直接把它当 List 用即可。
        //    null 判断保留：解析器在极端输入下返回 null 的历史行为不值得赌。
        if (statements == null || statements.size() != 1) {
            throw new BusinessException("只允许执行单条SQL");
        }
        return statements.get(0);
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

    private static void checkDangerousFunctions(SqlDialect dialect, List<String> functionNames) {
        for (String name : functionNames) {
            if (dialect.dangerousFunctions().contains(normalizeIdentifier(name))) {
                throw new BusinessException("SQL包含危险函数：" + name);
            }
        }
    }

    /**
     * 系统库检查，按方言分两步。
     *
     * <p>第一步看<b>库名前缀</b>；第二步看<b>不带前缀的表名</b> ——
     * 后者只有 PostgreSQL 需要，因为 {@code pg_catalog} <b>默认就在 search_path 里</b>，
     * {@code select * from pg_shadow} 不带前缀也能查到，只查前缀会整个漏掉。
     * MySQL 没有这个性质（它的系统库必须带前缀），所以那边这一集合是空的。
     */
    private static void checkSystemSchema(SqlDialect dialect, List<String> tableNames) {
        for (String tableName : tableNames) {
            String schema = extractSchema(tableName);
            if (schema != null && dialect.systemSchemas().contains(schema)) {
                throw new BusinessException("不允许访问系统库：" + schema);
            }

            // 不带库名前缀的表名：只有当整段名字就是那张表时才拦
            String bare = normalizeIdentifier(tableName);
            if (schema == null && dialect.dangerousTables().contains(bare)) {
                throw new BusinessException("不允许访问系统表：" + tableName);
            }
        }
    }

    private static void checkExecutableComment(String sqlWithoutLiterals) {
        if (sqlWithoutLiterals.contains(EXECUTABLE_COMMENT_MARKER)) {
            throw new BusinessException(
                    "SQL包含可执行注释（/*! ... */），其内容会被数据库真正执行，不允许使用");
        }
    }

    private static void checkIntoOutFile(String sqlWithoutLiteralsOrComments) {
        String lower = sqlWithoutLiteralsOrComments.toLowerCase(Locale.ROOT);
        if (lower.contains("into outfile") || lower.contains("into dumpfile")) {
            throw new BusinessException("SQL包含危险操作：不允许把查询结果写到服务器文件");
        }
    }

    /**
     * 把标识符归一化成"可以比较"的形式。
     *
     * <p>要去掉的是各种引用符：反引号（MySQL）、双引号（标准 SQL / ANSI_QUOTES）、
     * 方括号（SQL Server 风格）。这些符号在语法上不影响含义，
     * 但对字符串比较来说就是"绕过的空隙" —— 旧实现正是栽在这里。
     */
    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return identifier.replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /** 从可能带库名前缀的表名里取出库名；没有前缀时返回 {@code null}。 */
    private static String extractSchema(String tableName) {
        String normalized = normalizeIdentifier(tableName);
        int dot = normalized.indexOf('.');
        if (dot < 0) {
            return null;
        }
        return normalized.substring(0, dot).trim();
    }

    /**
     * 把字符串字面量整段替换成空格，其余部分原样保留。
     *
     * <p>为什么需要一个手写的小扫描器：第 4 步的两个检查必须在"文本"上做，
     * 但直接对原文做 {@code contains} 会误杀 —— {@code select 'into outfile' as note}
     * 是完全合法的查询，它的字面量里恰好有这几个字符。
     *
     * <p>扫描器只处理一件事：<b>哪里是字符串字面量</b>。它需要认识两种转义
     * （SQL 标准的 {@code ''} 与 MySQL 的 {@code \'}），否则一个转义引号
     * 就会让"字符串在哪里结束"判断错位，后面的内容会被错误地当成代码或字面量。
     *
     * <p>识别不出字符串时的行为是"吃到结尾"：宁可多屏蔽一点，
     * 也不要因为括号没配对就把剩下的内容当成代码去匹配。
     */
    static String stripStringLiterals(String sql, SqlDialect dialect) {
        StringBuilder output = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);

            if (c == '\'' || c == '"') {
                output.append(' ');
                i = skipQuoted(sql, i, c);
                continue;
            }

            // PostgreSQL 的 $tag$...$tag$ / $$...$$ 也是字符串字面量。
            // 不认它的话，一段含 "/*!" 或 "into outfile" 的字符串会被误判成危险 SQL。
            //
            // ⚠️ 这一步<b>必须按方言开</b>：MySQL 里 $ 是合法的标识符字符
            // （col$name 是合法列名），在那里把 $...$ 当字面量，会把真正的代码
            // 当成字符串屏蔽掉 —— 那是一个"漏检"，比误杀严重得多。
            if (c == '$' && dialect != null && dialect.supportsDollarQuoting()) {
                int end = skipDollarQuoted(sql, i);
                if (end > i) {
                    output.append(' ');
                    i = end;
                    continue;
                }
            }

            output.append(c);
            i++;
        }
        return output.toString();
    }

    /**
     * 跳过一个 PostgreSQL 的美元引用字符串。
     *
     * <p>形式有两种：{@code $$...$$} 和 {@code $tag$...$tag$}（tag 是标识符）。
     * 返回结束标记之后的下标；<b>认不出合法形式时返回原下标</b>，
     * 交给主循环当普通字符处理 —— 这一点很重要：PG 里 {@code $1} 是参数占位符，
     * 把它当引号开始会一路吃到结尾，把后面的代码全屏蔽掉。
     */
    private static int skipDollarQuoted(String sql, int start) {
        int tagEnd = start + 1;
        while (tagEnd < sql.length() && isDollarTagChar(sql.charAt(tagEnd))) {
            tagEnd++;
        }
        // 开标记必须以 $ 结尾，否则不是美元引用
        if (tagEnd >= sql.length() || sql.charAt(tagEnd) != '$') {
            return start;
        }

        String delimiter = sql.substring(start, tagEnd + 1);   // 含两端的 $
        int close = sql.indexOf(delimiter, tagEnd + 1);
        return close < 0 ? sql.length() : close + delimiter.length();
    }

    private static boolean isDollarTagChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 从 {@code start} 处的引号开始跳过一个字符串字面量，返回它后面第一个字符的下标。 */
    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\') {
                // 反斜杠转义：跳过被转义的字符，它不可能是字符串的结束符
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    // 两个连续引号表示一个字面量引号，字符串还没结束
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /**
     * 遍历语法树，收集表名与函数名。
     *
     * <p>直接复用 JSqlParser 的 {@link TablesNamesFinder} 而不是自己写访问者：
     * 它已经实现了 StatementVisitor / SelectVisitor / ExpressionVisitor 等全套接口，
     * 覆盖子查询、CTE、JOIN、UNION、EXISTS、IN 子查询这些容易漏掉的位置
     * （这些位置都实测验证过能被遍历到）。自己写一份只会漏得更隐蔽。
     */
    private static final class SqlInspector extends TablesNamesFinder {

        private final List<String> foundFunctions = new ArrayList<>();

        @Override
        public void visit(Function function) {
            if (function.getName() != null) {
                foundFunctions.add(function.getName());
            }
            // 必须调用父类实现：函数是可以嵌套的（例如 coalesce(sleep(1), 0)），
            // 不继续往下走就会漏掉参数里的危险函数
            super.visit(function);
        }
    }
}
