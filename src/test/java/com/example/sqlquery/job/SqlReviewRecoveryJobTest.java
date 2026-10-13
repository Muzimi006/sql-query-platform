package com.example.sqlquery.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.domain.ReviewStatus;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.mq.RabbitPublishConfirmer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.example.sqlquery.mq.RabbitConfig;

/**
 * 工单补偿任务。
 *
 * <h2>它处理两种卡死，而这两种的处置<b>刚好相反</b></h2>
 *
 * <ul>
 *   <li>卡在 APPROVED —— <b>重新投递</b>。安全，因为消费者是条件认领的，
 *       重复消息会被丢弃，不会重复执行。</li>
 *   <li>卡在 EXECUTING —— <b>标记失败，绝不重投</b>。
 *       写语句不幂等（{@code UPDATE ... SET n = n + 1} 跑两次就是两次），
 *       而中断时「到底生效没有」<b>无法确定</b>。</li>
 * </ul>
 *
 * <p>第二条是这个类存在的最重要的理由：<b>把不确定性交给人，
 * 而不是用一个「重试」把不确定性变成实际的错误数据。</b>
 */
class SqlReviewRecoveryJobTest {

    private SqlReviewRequestMapper reviewMapper;
    private RabbitTemplate rabbitTemplate;
    private RabbitPublishConfirmer publishConfirmer;
    private SqlReviewRecoveryJob job;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SqlReviewRequest.class);

        reviewMapper = Mockito.mock(SqlReviewRequestMapper.class);
        rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        // 默认：Broker 确认了这条消息。个别用例再改成 false（被拒 / 超时）
        publishConfirmer = Mockito.mock(RabbitPublishConfirmer.class);
        Mockito.when(publishConfirmer.awaitConfirmed(ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyString())).thenReturn(true);

        job = new SqlReviewRecoveryJob(reviewMapper, rabbitTemplate,
                Mockito.mock(SqlPlatformMetrics.class), publishConfirmer);

        ReflectionTestUtils.setField(job, "stuckApprovedMinutes", 10);
        ReflectionTestUtils.setField(job, "stuckApprovedMaxHours", 24);
        ReflectionTestUtils.setField(job, "stuckExecutingMinutes", 15);
        ReflectionTestUtils.setField(job, "publishConfirmTimeoutMs", 100L);
    }

    private SqlReviewRequest ticket(Long id, String status, LocalDateTime updatedAt) {
        SqlReviewRequest request = new SqlReviewRequest();
        request.setId(id);
        request.setUserId(1L);
        request.setStatus(status);
        request.setUpdatedAt(updatedAt);
        return request;
    }

    @SuppressWarnings("unchecked")
    private void stubPages(List<SqlReviewRequest> approved, List<SqlReviewRequest> executing) {
        Mockito.when(reviewMapper.selectPage(
                        ArgumentMatchers.<Page<SqlReviewRequest>>any(),
                        ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(page(approved), page(executing));
    }

    private Page<SqlReviewRequest> page(List<SqlReviewRequest> records) {
        Page<SqlReviewRequest> page = new Page<>();
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    @Test
    @DisplayName("APPROVED 卡住 → 重新投递执行消息")
    void stuckApprovedShouldBeResent() {
        stubPages(List.of(ticket(1L, ReviewStatus.APPROVED, LocalDateTime.now().minusMinutes(30))),
                List.of());

        job.recover();

        Mockito.verify(rabbitTemplate).convertAndSend(
                ArgumentMatchers.eq(RabbitConfig.REVIEW_QUEUE),
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.any(CorrelationData.class));
    }

    @Test
    @DisplayName("重复投递是安全的 —— 这正是「投递失败不报错、交给补偿任务」这个决定能成立的前提")
    void resendIsSafeBecauseClaimIsConditional() {
        stubPages(List.of(ticket(1L, ReviewStatus.APPROVED, LocalDateTime.now().minusMinutes(30))),
                List.of());

        job.recover();

        // 重投**不改状态**：APPROVED 仍然是对这一行最准确的描述（批了，还没执行）。
        // 如果这里顺手把状态改成别的，反倒会把一条本来能被正常消费的工单弄坏。
        //
        // ⚠️ 但它**会**更新 updated_at —— 而且是必须更新：
        //    selectStuck 的条件是 updated_at < now - stuckApprovedMinutes(10 分钟)，
        //    而 cron 周期是 5 分钟。不刷新的话下一轮立刻又选中它，
        //    一条卡住的工单 24 小时内会被重投 288 次。
        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), captor.capture());

        LambdaUpdateWrapper<SqlReviewRequest> wrapper = (LambdaUpdateWrapper<SqlReviewRequest>) captor.getValue();
        Map<String, Object> params = wrapper.getParamNameValuePairs();

        assertTrue(wrapper.getSqlSet().contains("updated_at"),
                "重投后必须刷新 updated_at，否则重投会被放大到每 5 分钟一次");
        assertFalse(params.values().contains(ReviewStatus.FAILED),
                "重投不能把状态改成 FAILED");
        assertTrue(wrapper.getSqlSegment().contains("status"),
                "更新必须带上状态条件（只能刷仍然 APPROVED 的那一行）");
    }

    @Test
    @DisplayName("⭐ 放弃重投的判定用 reviewedAt 而不是 updatedAt —— 否则永远放弃不了")
    void giveUpMustUseReviewedAtNotUpdatedAt() {
        // 批准时间在 30 小时前（超过 24h 上限），但 updated_at 是刚刚（因为一直在重投）
        SqlReviewRequest stale = ticket(1L, ReviewStatus.APPROVED, LocalDateTime.now());
        stale.setReviewedAt(LocalDateTime.now().minusHours(30));
        stubPages(List.of(stale), List.of());
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(1);

        job.recover();

        // 必须放弃重投（改成 FAILED），而不是又发一条消息
        Mockito.verify(rabbitTemplate, Mockito.never()).convertAndSend(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                ArgumentMatchers.any(CorrelationData.class));
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(),
                ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
    }

    @Test
    @DisplayName("重投超过上限就放弃，改成 FAILED —— 不能永远重投下去")
    void endlessResendMustBeGivenUp() {
        stubPages(List.of(ticket(1L, ReviewStatus.APPROVED, LocalDateTime.now().minusHours(30))),
                List.of());
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(1);

        job.recover();

        // 不能再发消息：Broker 长期不可用的情况下，每 5 分钟重投一次会刷满日志而没人注意
        Mockito.verify(rabbitTemplate, Mockito.never()).convertAndSend(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                ArgumentMatchers.any(CorrelationData.class));
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
    }

    @Test
    @DisplayName("EXECUTING 卡住 → 标记失败，并且**绝不**重新投递")
    void stuckExecutingMustFailWithoutRetry() {
        stubPages(List.of(),
                List.of(ticket(1L, ReviewStatus.EXECUTING, LocalDateTime.now().minusMinutes(60))));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(1);

        job.recover();

        // ⚠️ 这是整个类最重要的一条断言。
        //    写语句不幂等：UPDATE ... SET n = n + 1 跑两次就是加了两次。
        //    中断时"到底生效没有"无法确定，所以只能交给人核对，绝不能自动重跑。
        Mockito.verify(rabbitTemplate, Mockito.never()).convertAndSend(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                ArgumentMatchers.any(CorrelationData.class));
    }

    @Test
    @DisplayName("失败原因里必须写明「是否已生效无法确定」，而不是一句含糊的「执行失败」")
    void failureMessageMustBeHonestAboutUncertainty() {
        stubPages(List.of(),
                List.of(ticket(1L, ReviewStatus.EXECUTING, LocalDateTime.now().minusMinutes(60))));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(1);

        job.recover();

        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), captor.capture());

        LambdaUpdateWrapper<SqlReviewRequest> wrapper = (LambdaUpdateWrapper<SqlReviewRequest>) captor.getValue();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        String allValues = params.values().toString();

        assertTrue(wrapper.getSqlSet().contains("error_message"), "必须写失败原因，实际：" + wrapper.getSqlSet());
        assertTrue(allValues.contains("无法确定"),
                "必须说明这条 SQL 是否已经生效是未知的，实际：" + allValues);
        assertTrue(allValues.contains("核对"), "必须告诉人下一步做什么，实际：" + allValues);
    }

    @Test
    @DisplayName("条件更新影响 0 行说明工单刚被执行完，不能把 EXECUTED 覆盖成 FAILED")
    void shouldTolerateTicketFinishedInTheMeantime() {
        stubPages(List.of(),
                List.of(ticket(1L, ReviewStatus.EXECUTING, LocalDateTime.now().minusMinutes(60))));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(0);

        job.recover();

        // 不抛异常：这是正常的并发场景，不是错误
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
    }

    @Test
    @DisplayName("两种卡死都要扫到")
    void bothStuckStatesMustBeSwept() {
        stubPages(List.of(), List.of());
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(1);

        job.recover();

        // 两次查询：只扫一种的话，另一类卡死就永远没人管
        Mockito.verify(reviewMapper, Mockito.times(2)).selectPage(
                ArgumentMatchers.<Page<SqlReviewRequest>>any(),
                ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
    }

    @Test
    @DisplayName("单条投递失败不能把同一轮剩下的都跳过")
    void oneResendFailureMustNotStopTheRest() {
        stubPages(List.of(
                ticket(1L, ReviewStatus.APPROVED, LocalDateTime.now().minusMinutes(30)),
                ticket(2L, ReviewStatus.APPROVED, LocalDateTime.now().minusMinutes(30))),
                List.of());
        // 这个重载返回 void，只能用 doThrow 的形式桩住
        Mockito.doThrow(new org.springframework.amqp.AmqpException("broker down") {})
                .when(rabbitTemplate).convertAndSend(ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(Object.class), ArgumentMatchers.any(CorrelationData.class));

        job.recover();

        // 两条都尝试过。定时任务里「一个元素抛异常导致整批不处理」是很常见也很隐蔽的问题
        Mockito.verify(rabbitTemplate, Mockito.times(2)).convertAndSend(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                ArgumentMatchers.any(CorrelationData.class));
    }

    @Test
    @DisplayName("补偿查询必须带上限，补偿任务自己也不能把整张表捞进内存")
    void recoveryQueryMustBeBounded() {
        stubPages(List.of(), List.of());

        job.recover();

        ArgumentCaptor<Page<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Page.class);
        Mockito.verify(reviewMapper, Mockito.times(2)).selectPage(captor.capture(),
                ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
        assertTrue(captor.getValue().getSize() > 0 && captor.getValue().getSize() <= 1000,
                "补偿查询必须有批量上限，实际：" + captor.getValue().getSize());
    }

    @Test
    @DisplayName("判定用的是 updated_at（认领那一刻），不是 created_at")
    void stuckDetectionMustUseUpdatedAt() {
        stubPages(List.of(), List.of());

        job.recover();

        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper, Mockito.times(2))
                .selectPage(ArgumentMatchers.<Page<SqlReviewRequest>>any(), captor.capture());

        for (Wrapper<SqlReviewRequest> wrapper : captor.getAllValues()) {
            // 用 created_at 判的话，一条提交了两天、刚刚才轮到执行的工单
            // 会被立刻判成卡死 —— 而它其实正常得很
            assertTrue(wrapper.getSqlSegment().contains("updated_at"),
                    "卡死判定必须基于 updated_at，实际：" + wrapper.getSqlSegment());
            assertFalse(wrapper.getSqlSegment().contains("created_at"),
                    "不能基于 created_at，实际：" + wrapper.getSqlSegment());
        }
    }

    @Test
    @DisplayName("⭐ 投递未被确认时不许刷新 updated_at —— 否则重投会被一轮轮推迟")
    void unconfirmedPublishMustNotRefreshUpdatedAt() {
        stubPages(List.of(ticket(1L, ReviewStatus.APPROVED, LocalDateTime.now().minusMinutes(30))),
                List.of());

        // 队列满时 Broker 是**异步否定确认**：convertAndSend 照样平静返回，
        // 但消息其实没进队列。这时如果照样刷新 updated_at，
        // selectStuck 的筛选条件（updated_at < now - 10 分钟）就会认为
        // 这条工单「刚被处理过」，重投被推迟一轮又一轮，最坏拖到 24 小时的放弃时限。
        Mockito.when(publishConfirmer.awaitConfirmed(ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyString())).thenReturn(false);

        job.recover();

        Mockito.verify(rabbitTemplate).convertAndSend(
                ArgumentMatchers.eq(RabbitConfig.REVIEW_QUEUE),
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.any(CorrelationData.class));
        Mockito.verify(reviewMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
    }

    @Test
    @DisplayName("状态没到时什么都不做：既不发消息也不写库")
    void nothingStuckMeansNoOp() {
        stubPages(List.of(), List.of());

        job.recover();

        Mockito.verify(reviewMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<SqlReviewRequest>>any());
        Mockito.verify(rabbitTemplate, Mockito.never()).convertAndSend(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                ArgumentMatchers.any(CorrelationData.class));
    }
}
