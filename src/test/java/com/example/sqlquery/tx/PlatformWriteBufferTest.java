package com.example.sqlquery.tx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台库写缓冲。
 *
 * <p>它只有几十行代码，但有两件事一旦写错就很难查：
 * <ol>
 *   <li>嵌套：切面套切面时，内层不能把外层还没提交的写提前冲掉（begin() 的返回值约定）；</li>
 *   <li>清理：ThreadLocal 挂在会被 Tomcat 复用的线程上，漏清理会把上一个请求的写带到下一个请求。</li>
 * </ol>
 */
class PlatformWriteBufferTest {

    private final PlatformWriteBuffer buffer = new PlatformWriteBuffer();

    private PlatformWrite write(AtomicInteger counter) {
        return new PlatformWrite() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public void write() {
                counter.incrementAndGet();
            }

            @Override
            public void onFailure(Exception cause) {
            }
        };
    }

    @Test
    @DisplayName("没有开启时收集是空操作：调用方应当直接写库（单测/定时任务/MQ 消费者走这条路径）")
    void addWithoutBeginIsNoOp() {
        assertFalse(buffer.isActive());
        buffer.add(write(new AtomicInteger()));
        assertTrue(buffer.drain().isEmpty(), "没人收集时不该凭空攒下东西");
    }

    @Test
    @DisplayName("第一个 begin() 是开启者，嵌套的 begin() 不是（否则内层会把外层的写提前冲掉）")
    void onlyFirstBeginIsOwner() {
        assertTrue(buffer.begin(), "第一次开启应当是开启者");
        assertFalse(buffer.begin(), "嵌套开启不该自认为开启者");
        assertTrue(buffer.isActive());
        buffer.end();
        assertFalse(buffer.isActive());
    }

    @Test
    @DisplayName("drain 取出并清空：可以分两批提交，第一批不会在第二批里重复出现")
    void drainReturnsSnapshotAndClears() {
        buffer.begin();
        buffer.add(write(new AtomicInteger()));
        buffer.add(write(new AtomicInteger()));

        List<PlatformWrite> first = buffer.drain();
        assertEquals(2, first.size());
        assertTrue(buffer.drain().isEmpty(), "drain 之后应当已经清空");
        assertTrue(buffer.isActive(), "drain 不结束收集 —— 结束是 end() 的事");
        buffer.end();
    }

    @Test
    @DisplayName("end 清掉 ThreadLocal：线程被复用时不带残留（这是它最关键的一条）")
    void endClearsThreadLocal() {
        buffer.begin();
        buffer.add(write(new AtomicInteger()));
        buffer.end();

        assertFalse(buffer.isActive());
        assertEquals(0, buffer.size());
        assertTrue(buffer.begin());
        assertEquals(0, buffer.size(), "新一批不该看到上一批的残留");
        buffer.end();
    }
}
