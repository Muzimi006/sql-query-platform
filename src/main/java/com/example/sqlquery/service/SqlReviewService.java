package com.example.sqlquery.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.vo.SqlReviewRequestVO;

/**
 * SQL 审核工单。
 *
 * <h2>它解决的是什么问题</h2>
 *
 * <p>在这个平台上，"能不能执行一条写语句"原本只有一个答案：
 * 看角色的白名单。但真实业务里还有第二个问题：
 * <b>「就算我有权限，这一条具体的 SQL 现在该不该跑？」</b>
 *
 * <p>票务、文物档案这类数据，一条写错的 {@code UPDATE} 改的是钱和档期，
 * 而"角色是管理员"这个事实，对"这一条 SQL 写得对不对"毫无帮助。
 * 工单把这两件事拆开：
 *
 * <ul>
 *   <li><b>角色白名单</b>回答「有没有资格执行写语句」（{@link SqlPermissionService}）；</li>
 *   <li><b>工单</b>回答「这一条该不该放行」—— 由另一个人看过后决定。</li>
 * </ul>
 *
 * <h2>一个必须说清楚的设计点：能申请 ≠ 能执行</h2>
 *
 * <p>提交工单用的白名单是 {@code platform.sql-permission.reviewable-types}
 * （默认 INSERT / UPDATE / DELETE），<b>不是</b>角色白名单。
 * 如果沿用角色白名单，只有 SELECT 权限的 USER 连一条 UPDATE 的工单都提交不了 ——
 * 而"让没有写权限的人能申请写操作"正是工单存在的理由。
 *
 * <h2>执行的是工单里存的那条 SQL</h2>
 *
 * <p>审批接口不接受 SQL 参数（见 {@link com.example.sqlquery.dto.SqlReviewDecisionDTO}），
 * 执行时读的是工单行里的 {@code sql_text}。
 * 否则就会出现"审批人看到 A、实际执行 B"—— 那是审批类系统最经典的漏洞。
 */
public interface SqlReviewService {

    /**
     * 提交一条人工手写的工单。
     *
     * @param userId 申请人
     * @return 新建（或幂等命中的已存在）工单
     */
    SqlReviewRequestVO submit(Long userId, SqlReviewSubmitDTO dto);

    /**
     * 提交一条 <b>AI 生成</b>的工单。
     *
     * <p>与 {@link #submit} 唯一的区别是来源标记和 prompt 留痕。
     * <b>校验、影响面分析、审批、执行全部走同一条路径</b> ——
     * AI 不因为它是 AI 而获得任何特权，也不因为它是 AI 而被更宽松地对待。
     * 这一点是刻意的：如果给 AI 单开一条"跳过某道检查"的路径，
     * 那条路径就会成为整个系统里最薄弱的一环。
     *
     * @param prompt 用户当时的自然语言问题，存下来给审批人看"意图对不对"
     */
    SqlReviewRequestVO submitGenerated(Long userId, SqlReviewSubmitDTO dto, String prompt);

    /** 我提交的工单。 */
    IPage<SqlReviewRequestVO> pageMine(Long userId, long current, long size);

    /**
     * 待我审批的工单（审批队列）。
     *
     * <p>只有管理员能看 —— 这是别人提交的待执行 SQL，属于敏感内容。
     */
    IPage<SqlReviewRequestVO> pagePending(long current, long size);

    /**
     * 工单详情。
     *
     * <p>申请人可以看自己的，管理员可以看所有的。
     * 其他人拿到的是「工单不存在」而不是「无权限」—— 见实现里的说明。
     */
    SqlReviewRequestVO detail(Long userId, Long id);

    /** 批准。批准之后<b>异步</b>执行，不阻塞审批人的请求。 */
    void approve(Long reviewerId, Long id, String comment);

    /** 驳回。<b>必须写原因</b>。 */
    void reject(Long reviewerId, Long id, String comment);
}
