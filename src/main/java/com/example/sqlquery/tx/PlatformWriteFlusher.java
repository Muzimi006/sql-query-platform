package com.example.sqlquery.tx;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.List;

/**
 * 把攒下来的平台库写在<b>一个事务</b>里提交。

 * <h2>它省下的到底是什么</h2>

 * <p>本项目跑的是 MySQL <b>双1</b>（`sync_binlog=1` + `innodb_flush_log_at_trx_commit=1`）：
 * 每次提交都要 fsync redo + binlog（两阶段提交）= <b>2 次 fsync</b>（口径见档案 G-08）。
 * 而一次查询请求原本写两张表、各自提交 —— 也就是 <b>2 次提交 / 4 次 fsync</b>。
 * 合并之后是 <b>1 次提交 / 2 次 fsync</b>。

 * <p>实测过这条结论的方向：双1 + 逐行提交 <b>175 行/秒</b>，双1 + 单事务 <b>7,053 行/秒</b>。
 * <b>减少提交次数，比牺牲耐久性划算得多</b>（后者是"把地基换掉"）。

 * <h2>⚠️ 合并带来的一个真实取舍：一条失败会不会带走另一条</h2>

 * <p>会 —— 如果什么都不做的话：两条 insert 在同一个事务里，第一条失败会让整个事务回滚，
 * 于是「审计没写进去」和「历史没写进去」变成同一件事。
 * 而它们的语义本来互不依赖（审计是安全要求，历史是附属品）。

 * <p>所以这里用 <b>保存点（savepoint）</b> 把它们隔开：失败的那一条回滚到自己的保存点，
 * 另一条照常提交。<b>一次提交 + 两条互不牵连</b>，两个性质都保住了。

 * <h2>⚠️ 失败为什么不往外抛</h2>

 * <p>因为这两条写都是<b>附属品</b>：用户的那条 SQL 该不该算成功，早就在业务层定了
 * （历史写失败不该把一次成功的执行变成"系统异常"，否则用户重试就是重复改一次数据；
 * 审计写失败同样不该影响业务）。失败只记各自指定的计数与日志。
 */
@Slf4j
@Component
public class PlatformWriteFlusher {

    private final SqlPlatformMetrics metrics;

    public PlatformWriteFlusher(SqlPlatformMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 在<b>一个事务</b>里提交这批写。
     *
     * @return 真正写成功的条数（失败的那些已经各自记过账了）
     */
    @Transactional(rollbackFor = Exception.class)
    public int flush(List<PlatformWrite> writes) {
        if (writes == null || writes.isEmpty()) {
            return 0;
        }

        int succeeded = 0;
        for (PlatformWrite write : writes) {
            Object savepoint = null;
            try {
                // ⚠️ 保存点必须在 write() **之前**创建：创建本身也可能失败（连接不可用），
                //    那种情况下面的 catch 会拿到 savepoint == null，直接走失败处理。
                savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
                write.write();
                succeeded++;
            } catch (Exception e) {
                rollbackTo(savepoint, write, e);
            }
        }

        // ⭐ 这两个计数器是「合并写生效了」唯一的可靠证据（为什么不用 Com_commit 见指标那边的注释）
        metrics.recordWriteFlush(writes.size(), succeeded);
        log.debug("平台库写合并提交：{} 条成功 / {} 条待写", succeeded, writes.size());
        return succeeded;
    }

    /**
     * 回滚到保存点并交给这条写自己记账。
     *
     * <p>⚠️ 回滚保存点<b>本身也可能失败</b>（连接已经断了）—— 那时事态已经超出"能不能保住另一条"的范畴，
     * 只记一条 WARN，剩下的交给各自的失败计数。
     */
    private void rollbackTo(Object savepoint, PlatformWrite write, Exception cause) {
        if (savepoint != null) {
            try {
                TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
            } catch (Exception rollbackFailure) {
                log.warn("回滚保存点失败（连接可能已断），这条写无法与其它写隔离。write={}",
                        write.name(), rollbackFailure);
            }
        }
        write.onFailure(cause);
    }
}
