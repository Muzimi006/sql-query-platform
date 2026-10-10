package com.example.sqlquery.tx;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求级的平台库写缓冲。

 * <h2>它解决什么</h2>

 * <p>把「一次请求里的多次平台库写」攒起来，交给 {@link PlatformWriteFlusher} 一次事务提交 ——
 * 从 2 次提交降到 1 次。背景与实测数字见 {@link PlatformWrite} 的类注释。

 * <h2>⚠️ 为什么是 ThreadLocal，以及它的边界</h2>

 * <ul>
 *   <li>它只在<b>当前线程</b>有效。本项目所有平台库写都在请求线程上（审计切面、查询历史），
 *       所以够用；但如果将来有写操作搬到别的线程（`@Async`、MQ 消费者），
 *       <b>它不会自动跟过去</b>，那种场景应当直接写库而不是用这个缓冲。</li>
 *   <li>{@link #end()} 必须放在 {@code finally} 里：ThreadLocal 在 Tomcat 的
 *       线程池里是<b>会被复用的</b>，漏清理会让下一个请求看到上一个请求的残留。</li>
 *   <li>用 {@link #isActive()} 判断「当前有没有人在收集」：<b>没有收集者时调用方应当直接写库</b>
 *       （单测、定时任务、MQ 消费者都走这条路径）。</li>
 * </ul>
 */
@Component
public class PlatformWriteBuffer {

    private final ThreadLocal<List<PlatformWrite>> pending = new ThreadLocal<>();

    /** 当前线程是否已经在收集（由最外层开启，见 {@link #begin()} 的返回值约定）。 */
    public boolean isActive() {
        return pending.get() != null;
    }

    /**
     * 开始收集。
     *
     * @return {@code true} 表示<b>这一次调用是开启者</b>（之前没有人在收集）——
     *         只有开启者才负责在结束时 flush 并 {@link #end()}。
     *         这样嵌套调用（切面套切面）不会把内层的缓冲提前冲掉。
     */
    public boolean begin() {
        if (pending.get() != null) {
            return false;
        }
        pending.set(new ArrayList<>(4));
        return true;
    }

    /** 收集一次写。没有开启时**静默忽略**（调用方应当先用 {@link #isActive()} 判断）。 */
    public void add(PlatformWrite write) {
        List<PlatformWrite> list = pending.get();
        if (list != null) {
            list.add(write);
        }
    }

    /** 取出并清空当前收集到的写（不结束收集）。 */
    public List<PlatformWrite> drain() {
        List<PlatformWrite> list = pending.get();
        if (list == null) {
            return List.of();
        }
        List<PlatformWrite> copy = List.copyOf(list);
        list.clear();
        return copy;
    }

    /**
     * 结束收集并清理 ThreadLocal。
     *
     * <p>⚠️ <b>必须调用</b>：Tomcat 线程池会复用线程，不清理就会把残留带给下一个请求
     * （后果是"上一个请求的审计被算到下一个请求上"，而且极难复现）。
     */
    public void end() {
        pending.remove();
    }

    /** 仅供测试：当前收集到几条。 */
    int size() {
        List<PlatformWrite> list = pending.get();
        return list == null ? 0 : list.size();
    }
}
