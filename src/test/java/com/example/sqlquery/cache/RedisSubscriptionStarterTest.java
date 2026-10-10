package com.example.sqlquery.cache;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Redis 失效广播订阅的<b>看门狗</b>。
 *
 * <p>补的是一个很隐蔽的洞：{@code subscribed} 这个标记只表达"曾经订阅成功"。
 * 容器如果在运行中彻底死掉（连接断开且重连无望），这个标记不会变，
 * 于是重试逻辑再也进不来 —— <b>失效广播就静默地停了，直到进程重启为止</b>。
 *
 * <p>它属于"只有长时间不重启的进程才会遇到"的那类故障，所以必须有用例钉住。
 * 这期间的外部表现是多实例部署时"改了数据源，另一台机器的连接池还是旧的"，
 * 而日志和指标里<b>什么都没有</b>。
 */
class RedisSubscriptionStarterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);
    private RedisConnectionFactory connectionFactory;
    private RedisSubscriptionStarter starter;

    @BeforeEach
    void setUp() {
        connectionFactory = Mockito.mock(RedisConnectionFactory.class);
        starter = new RedisSubscriptionStarter(connectionFactory,
                Mockito.mock(DbSourceCacheInvalidationListener.class), metrics);
    }

    @AfterEach
    void tearDown() {
        starter.stop();
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RedisSubscriptionStarter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(starter, value);
    }

    private Object getField(String name) throws Exception {
        Field field = RedisSubscriptionStarter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(starter);
    }

    private double up() {
        Gauge gauge = registry.find(SqlPlatformMetrics.REDIS_SUBSCRIBER_UP).gauge();
        assertNotNull(gauge, "订阅状态必须是一条能看见的曲线，不能只在启动日志里说一次");
        return gauge.value();
    }

    private double restarts() {
        var counter = registry.find(SqlPlatformMetrics.REDIS_SUBSCRIBER_RESTART).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("还没订阅成功时，指标必须是 0（而不是默认看起来正常）")
    void shouldReportDownBeforeSubscription() {
        assertEquals(0.0, up());
        assertEquals(0.0, restarts());
    }

    @Test
    @DisplayName("⭐ 曾经订阅成功、容器却已不在跑 —— 必须被发现并重建")
    void watchdogShouldRestartDeadContainer() throws Exception {
        RedisMessageListenerContainer dead = Mockito.mock(RedisMessageListenerContainer.class);
        Mockito.when(dead.isRunning()).thenReturn(false);
        setField("subscribed", true);
        setField("container", dead);

        // 修复前：这一句会直接 return（因为 subscribed == true），故障永远不会被发现
        starter.retryIfNotSubscribed();

        assertEquals(1.0, restarts(), "容器死了却没有被重建");
        // 死掉的实例必须被真正销毁并丢弃 —— 复用它正是类注释里那个
        // "日志说已启动、PUBSUB NUMSUB 却是 0"的假成功
        Mockito.verify(dead).destroy();
        assertNotSame(dead, getField("container"), "死掉的容器必须被换成新建的实例");
    }

    @Test
    @DisplayName("容器活得好好的时候不许乱动它（看门狗只可能漏报，不能误报）")
    void watchdogShouldNotTouchHealthyContainer() throws Exception {
        RedisMessageListenerContainer healthy = Mockito.mock(RedisMessageListenerContainer.class);
        Mockito.when(healthy.isRunning()).thenReturn(true);
        setField("subscribed", true);
        setField("container", healthy);

        starter.retryIfNotSubscribed();

        assertEquals(0.0, restarts());
        assertSame(healthy, getField("container"), "健康的容器不该被销毁重建");
    }
}
