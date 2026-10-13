package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.common.DbSourceStatus;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.DbSourceMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.AesUtil;
import com.example.sqlquery.util.JdbcUrlUtil;
import com.example.sqlquery.vo.DbSourceVO;
import com.example.sqlquery.cache.DbSourceCacheInvalidator;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Duration;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DbSourceServiceImpl extends ServiceImpl<DbSourceMapper, DbSource> implements DbSourceService {

    private final AesUtil aesUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DbSourceCacheInvalidator invalidator;
    private final SqlPlatformMetrics metrics;

    private static final String LOCK_PREFIX = "lock:datasource:";

    /**
     * 缓存 key 的唯一构造入口：读、写、删都必须走这里，避免多处拼接不一致。
     *
     * <p>真正的拼接在 {@link DbSourceCacheInvalidator#cacheKey(Long)} ——
     * 因为广播的接收端也要删同一个键，key 的构造必须只有一处实现。
     */
    private String buildCacheKey(Long id) {
        return DbSourceCacheInvalidator.cacheKey(id);
    }

    /**
     * 不存在的 id 也要缓存（空值缓存），否则每次查一个不存在的 id 都会穿透到数据库。
     *
     * <p>用哨兵字符串而不是真的 {@code null}：Redis 里存不了 {@code null}，
     * 而"键不存在"和"键的值是空"必须是两种可区分的状态 ——
     * 前者要回源、后者要直接拒绝。
     */
    private static final String NULL_SENTINEL = "NULL";

    private static final Duration NULL_SENTINEL_TTL = Duration.ofMinutes(5);

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** TTL 的随机抖动上限（秒），用来打散过期时间，避免大量缓存同时失效（缓存雪崩）。 */
    private static final int CACHE_TTL_JITTER_SECONDS = 300;

    /** 分布式锁的持有时长：超过这个时间没释放（持锁进程挂了），锁自动过期，不会永久卡死。 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /**
     * 没抢到分布式锁时的<b>有界</b>等待：等别的实例把缓存建好。
     *
     * <p>这两个数字的取值依据：回源是一条按主键的查询加上一次 Redis 写入，
     * 正常在 1~5 毫秒量级；500 毫秒已经比它宽两个数量级，
     * 足以覆盖绝大多数情况，又不会让 Tomcat 线程被长时间占住。
     * <b>它们描述的是"等待多久算异常"，不是"回源大概要多久"</b> ——
     * 这一点和原来拍脑袋写死的 {@code sleep(50)} 有本质区别。
     */
    private static final long WAIT_FOR_REBUILD_TIMEOUT_MS = 500;

    private static final long WAIT_FOR_REBUILD_INTERVAL_MS = 20;

    /**
     * JVM 内的分段锁（lock striping）。
     *
     * <p>数组长度固定，所以<b>不会有内存泄漏</b>：如果按 key 建一个
     * {@code ConcurrentHashMap<String, Object>} 当锁表，数据源删掉后锁对象还在，
     * 这是一个缓慢增长的泄漏；而分段锁的空间是常数。
     *
     * <p>代价是不同 key 偶尔会落到同一把锁上（哈希冲突），表现为两个无关的数据源
     * 之间多了一点点串行 —— 只影响吞吐，不影响正确性，这个取舍是划算的。
     *
     * <p>分段数是 2 的幂，配合 {@link Math#floorMod} 做映射；
     * 取 64 是因为冲突概率已经足够低（数据源数量通常是个位数到几十）。
     */
    private static final int LOCK_STRIPES = 64;

    private final Object[] localLocks = new Object[LOCK_STRIPES];

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class
    );


    public DbSourceServiceImpl(AesUtil aesUtil, StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper, DbSourceCacheInvalidator invalidator,
                              SqlPlatformMetrics metrics) {
        this.aesUtil = aesUtil;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.invalidator = invalidator;
        this.metrics = metrics;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.localLocks[i] = new Object();
        }
    }

    @Override
    public void addDbSource(Long userId, DbSourceDTO dto) {
        // ⚠️ 密码非空校验在这里，不在 DTO 上：DTO 被新增和编辑共用，
        //    而编辑时「留空 = 不修改」是合法的。见 DbSourceDTO.password 的注释。
        if (dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new BusinessException("数据库密码不能为空");
        }
        if (!checkNameUnique(userId, dto.getName())) {
            throw new BusinessException("数据源名称已存在");
        }
        DbSource dbSource = toEntity(dto);
        dbSource.setUserId(userId);
        dbSource.setStatus(DbSourceStatus.ENABLED);
        dbSource.setPasswordEncrypted(aesUtil.encrypt(dto.getPassword()));

        try {
            save(dbSource);
        } catch (DuplicateKeyException e) {
            // 上面的 checkNameUnique 是「先查再插」，并发下两个请求可能同时查到「不重名」，
            // 这时由唯一索引 uk_user_id_name 兜底。不翻译的话会落到全局处理器，
            // 报成「用户名已存在」—— 提示是错的。
            throw new BusinessException("数据源名称已存在");
        }
    }

    /**
     * 列表查询。
     *
     * <p>必须带排序：数据库<b>不保证</b>无 ORDER BY 时的返回顺序，
     * 加了删除和新增之后顺序会变得不可预测（实测过：同一份数据两次请求顺序不同）。
     * 用 created_at 倒序是"最近添加的在最上面"，符合使用直觉。
     *
     * <p>再补一个 id 倒序作为<b>并列时的确定性兜底</b>：
     * created_at 只精确到秒，同一秒内建的多条数据源会并列，
     * 只按 created_at 排序时它们之间的顺序仍然是随机的。
     */
    @Override
    public List<DbSourceVO> listByUserId(Long userId) {
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId, userId)
               .orderByDesc(DbSource::getCreatedAt)
               .orderByDesc(DbSource::getId);
        return list(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public void updateDbSource(Long userId, Long id, DbSourceDTO dto) {
        DbSource dbSource = toEntity(dto);
        DbSource exist = getById(id);
        if(exist == null || !exist.getUserId().equals(userId)){
            throw new BusinessException("数据源不存在");
        }
        if (!checkNameUniqueExcludeId(userId, dto.getName(), id)) {
            throw new BusinessException("数据源名称已存在");
        }
        dbSource.setId(id);
        dbSource.setUserId(userId);
        // ⭐ 密码留空 = 不修改：出参永不下发密码，所以编辑对话框里密码框当然是空的；
        //    如果这里无条件用 dto 的值覆盖，用户为了改个名字就必须重新输入一遍密码。
        //    ⚠️ 必须回填**库里那条密文**（exist.getPasswordEncrypted()），不能把空串加密后写进去 ——
        //    后者会把数据源改成「密码为空」的状态，下一次连接必然失败，而且报错与"密码错"一模一样。
        String rawPassword = dto.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            dbSource.setPasswordEncrypted(exist.getPasswordEncrypted());
        } else {
            dbSource.setPasswordEncrypted(aesUtil.encrypt(rawPassword));
        }

        try {
            updateById(dbSource);
        } catch (DuplicateKeyException e) {
            // 改名到另一个已存在的名字，同样由唯一索引兜底
            throw new BusinessException("数据源名称已存在");
        }

        // 修改可能改了连接串或密码 —— 必须让所有实例的连接池都失效，
        // 否则别的实例会拿着旧密码的连接池继续跑，表现为间歇性失败
        invalidator.invalidateEverywhere(id);
    }

    @Override
    public void deleteDbSource(Long userId, Long id) {
        DbSource exist = getById(id);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BusinessException("数据源不存在");
        }
        removeById(id);
        invalidator.invalidateEverywhere(id);
    }

    @Override
    // ⚠️ 抑制的是 javac 的 -Xlint:try 警告："资源 connection 在 try 体内从未被引用"。
    //    这里是**误报**：连接对象的用途就是"建得起来 + 用完关掉"，不引用它是有意的
    //    （加一句 connection.isValid(1) 能消掉警告，但那会多发一次往返，
    //      而"连得上"在 getConnection 正常返回时就已经成立）。
    //    换 Java 9 的"无变量资源"写法也不行：那个语法只接受**变量**，不接受方法调用表达式。
    @SuppressWarnings("try")
    public boolean testConnection(DbSourceDTO dto) {
        DbSource dbSource = toEntity(dto);
        String url = JdbcUrlUtil.build(dbSource);
        // ⚠️ 显式设一个登录超时：连接串里的 connectTimeout **管不到 DNS 解析阶段**，
        //    而这正是"把一个不可达主机名填进来"时最容易卡住的地方。
        //    DriverManager 是全局设置，设成 5 秒与 JdbcUrlUtil 的 connectTimeout 对齐。
        DriverManager.setLoginTimeout(5);
        try (Connection connection = DriverManager.getConnection(
                url,
                dbSource.getUsername(),
                dto.getPassword())) {
            // 刻意不使用 connection：能走到这一行就说明连上了（抑制原因见方法上的注释）
            return true;
        }catch (SQLException e){
            throw new BusinessException("无法连接到数据库：" + e.getMessage());
        }
    }

    @Override
    public IPage<DbSourceVO> pageByUserId(Long userId, long current, long size) {
        Page<DbSource> page = new Page<>(current, size);
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId, userId)
               .orderByDesc(DbSource::getCreatedAt)
               .orderByDesc(DbSource::getId);

        Page<DbSource> result = page(page, wrapper);

        Page<DbSourceVO> voPage = new Page<>(current, size, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 按 id 取数据源（带缓存）。
     *
     * <h2>缓存击穿是怎么被真正堵住的</h2>
     *
     * <p>击穿指的是：某个热 key 过期的那一瞬间，大量并发请求同时发现缓存没了，
     * 于是一起涌向数据库。原来的实现是"抢不到 Redis 锁就 {@code sleep(50)} 再读一次缓存，
     * 还没命中就直接查库" —— 它只降低了概率，没有根除：
     * 50 毫秒是拍脑袋定的（没有按回源耗时的 P99 度量过），
     * 而且等待者最终仍然会去查库，QPS 一高照样把数据库打穿。
     *
     * <p>现在的做法是两道锁，各自负责一段：
     *
     * <ol>
     *   <li><b>JVM 内分段锁（{@link #localLocks}）</b>：同一个 key 在本进程内
     *       只允许一个线程回源，其余线程阻塞等它。
     *       这一层就足以让<b>单实例部署</b>下同一 key 的数据库访问从 N 次降到 1 次
     *       —— 也就是"根除"，而不是"降低概率"。</li>
     *   <li><b>Redis 分布式锁</b>：多实例部署时，让跨实例也只有一个回源者。
     *       没抢到锁的实例走 {@link #waitForRebuild}：<b>有界</b>轮询等缓存出现，
     *       拿到就用；等不到才自己回源，并打 WARN 记录这条降级路径。
     *       等不到是显式选择的兜底，不是没想过。</li>
     * </ol>
     *
     * <p>为什么不是"逻辑过期 + 异步重建"：那种方案能让等待者<b>永远不阻塞</b>、
     * 代价是短时间返回旧数据。本项目的数据源元数据（启用/停用、密码、连接串）
     * 一旦变更就必须立刻生效 —— 返回"已停用"的旧值意味着用户仍能执行 SQL，
     * 这是安全问题，不能用"最终一致"换那点延迟。所以这里选择让等待者短暂阻塞。
     */
    @Override
    public DbSource getByIdAndUserId(Long userId, Long id) {
        String cacheKey = buildCacheKey(id);

        DbSource cached = readCacheOrThrow(cacheKey);
        if (cached != null) {
            return cached;
        }

        synchronized (localLocks[Math.floorMod(cacheKey.hashCode(), LOCK_STRIPES)]) {
            // 双重检查：等锁的这段时间里，拿到锁的那个线程很可能已经把缓存建好了
            DbSource rebuiltWhileWaiting = readCacheOrThrow(cacheKey);
            if (rebuiltWhileWaiting != null) {
                return rebuiltWhileWaiting;
            }
            return loadAndCache(userId, id, cacheKey);
        }
    }

    @Override
    public DbSource getEnabledByIdAndUserId(Long userId, Long id) {
        DbSource dbSource = getByIdAndUserId(userId, id);

        // 常量放在左边：status 字段在库里可空，写成 dbSource.getStatus().equals(...) 会 NPE
        if (!Integer.valueOf(DbSourceStatus.ENABLED).equals(dbSource.getStatus())) {
            throw new BusinessException("数据源已停用");
        }
        return dbSource;
    }

    /**
     * 回源并写缓存。<b>调用方必须已经持有该 key 的本地分段锁。</b>
     *
     * <p>内部再用一把 Redis 锁，是为了让多实例部署时跨进程也只有一个人回源。
     */
    private DbSource loadAndCache(Long userId, Long id, String cacheKey) {
        String lockKey = LOCK_PREFIX + id;
        String lockValue = UUID.randomUUID().toString();
        boolean locked = tryLockQuietly(lockKey, lockValue);

        if (!locked) {
            // 没抢到分布式锁 = 另一个实例正在回源。有界等待它把缓存建好。
            DbSource rebuiltByOtherInstance = waitForRebuild(cacheKey);
            if (rebuiltByOtherInstance != null) {
                return rebuiltByOtherInstance;
            }
            // 等不到（对方失败或超时）：自己回源。
            // 这是显式的降级选择 —— 相比"让请求失败"，多查一次库的代价更小。
            log.warn("等待其他实例重建缓存超时，本实例直接回源。dataSourceId={}, 等待上限={}ms",
                    id, WAIT_FOR_REBUILD_TIMEOUT_MS);
        }

        try {
            DbSource dbSource = getById(id);
            if (dbSource == null || !dbSource.getUserId().equals(userId)) {
                // 缓存在这里也走统一入口写，保证"读、写、删"三处 key 一致
                setCacheQuietly(cacheKey, NULL_SENTINEL, NULL_SENTINEL_TTL);
                throw new BusinessException("数据源不存在");
            }

            long randomTtl = CACHE_TTL.getSeconds() + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS);
            String cacheValue = writeJsonQuietly(dbSource);
            if (cacheValue != null) {
                setCacheQuietly(cacheKey, cacheValue, Duration.ofSeconds(randomTtl));
            }
            return dbSource;
        } finally {
            if (locked) {
                unlockQuietly(lockKey, lockValue);
            }
        }
    }

    /**
     * 有界轮询等待缓存被别的实例建好。
     *
     * <p>返回 {@code null} 表示在 {@link #WAIT_FOR_REBUILD_TIMEOUT_MS} 内都没等到，
     * 由调用方决定怎么降级。
     */
    private DbSource waitForRebuild(String cacheKey) {
        long deadline = System.currentTimeMillis() + WAIT_FOR_REBUILD_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(WAIT_FOR_REBUILD_INTERVAL_MS);
            // readCacheOrThrow 命中 NULL 哨兵时会直接抛「数据源不存在」，
            // 这是正确的：确认不存在，不需要再等也不需要回源
            DbSource cached = readCacheOrThrow(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    /**
     * 读缓存，三态语义：
     * <ul>
     *   <li>命中实体 → 返回实体</li>
     *   <li>命中 {@link #NULL_SENTINEL} → 抛「数据源不存在」（不回源，这是空值缓存的意义）</li>
     *   <li>未命中 → 返回 {@code null}，由调用方回源</li>
     * </ul>
     */
    private DbSource readCacheOrThrow(String cacheKey) {
        String cached = getCacheQuietly(cacheKey);
        if (cached == null) {
            // 计数放在这里而不是调用方：命中与否就是"能不能省掉一次数据库查询"，
            // 这个判断只在读到值的那一刻才真正确定（包括下面命中空值哨兵的情况 ——
            // 它同样省掉了一次查询，所以算命中）
            metrics.recordCacheMiss();
            return null;
        }
        metrics.recordCacheHit();

        if (NULL_SENTINEL.equals(cached)) {
            throw new BusinessException("数据源不存在");
        }
        try {
            return objectMapper.readValue(cached, DbSource.class);
        } catch (Exception e) {
            // 缓存内容坏了（例如实体改了字段名，旧结构反序列化不了）。
            // 必须删掉这条脏缓存，否则每次请求都会走到这里、每次都白解析一遍。
            log.warn("缓存反序列化失败，已删除该缓存并回源。cacheKey={}", cacheKey, e);
            deleteCacheByKey(cacheKey);
            return null;
        }
    }

    private boolean tryLockQuietly(String lockKey, String lockValue) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, LOCK_TTL));
        } catch (Exception e) {
            // Redis 不可用：当作"没抢到锁"处理，交给 waitForRebuild 走有界等待 →
            // 超时后回源。这样 Redis 挂了只是失去缓存，不会让接口整体不可用。
            metrics.recordRedisDegradation("cache-lock", "lockKey=" + lockKey, e);
            return false;
        }
    }

    private void unlockQuietly(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            // 释放失败没关系：锁上有 TTL，到点自动消失。
            // 这一条连降级都算不上（不影响正确性），只是留个痕迹，所以不进统一出口。
            log.warn("释放缓存回源锁失败（锁会由 TTL 自动过期）。lockKey={}", lockKey);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉 InterruptedException 会让上层永远看不到"该停了"
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 读缓存。失败返回 {@code null}（当作未命中，由调用方回源）。
     *
     * <p>⚠️ 这里<b>原来是完全静默的</b>（连日志都没有）。静默降级的后果是：
     * Redis 挂掉之后，接口全都正常返回 200，<b>没有任何地方能看出来缓存已经失效了</b> ——
     * 只能从"数据库压力变大"这种间接现象去猜。
     *
     * <p>改成走统一降级出口之后，至少有一个计数器
     * {@code redis.degradation{reason="cache-read"}} 和每 30 秒一条的 WARN。
     */
    private String getCacheQuietly(String cacheKey) {
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            metrics.recordRedisDegradation("cache-read", "cacheKey=" + cacheKey, e);
            return null;
        }
    }

    /** 写缓存。失败不影响主流程，但同样要留下痕迹（原来也是静默的）。 */
    private void setCacheQuietly(String cacheKey, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttl);
        } catch (Exception e) {
            metrics.recordRedisDegradation("cache-write", "cacheKey=" + cacheKey, e);
        }
    }

    private String writeJsonQuietly(DbSource dbSource) {
        try {
            return objectMapper.writeValueAsString(dbSource);
        } catch (JsonProcessingException e) {
            log.warn("数据源序列化失败，本次不写缓存。id={}", dbSource.getId(), e);
            return null;
        }
    }

    @Override
    public boolean checkNameUnique(Long userId, String name) {
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId, userId)
               .eq(DbSource::getName, name);
        return count(wrapper) == 0;
    }

    @Override
    public void changeStatus(Long userId, Long id, Integer status) {
        // 状态值只允许在 ENABLED / DISABLED 之间切换。
        // 不做这个校验的话，PUT /api/datasource/{id}/status?status=99 会把 99 原样写进库：
        // 行为上看着像「停用」（比较用的是 equals(ENABLED)），但库里留下的是脏值，
        // 以后按 where status = 0 统计就查不到这条记录。
        boolean validStatus = Integer.valueOf(DbSourceStatus.ENABLED).equals(status)
                || Integer.valueOf(DbSourceStatus.DISABLED).equals(status);
        if (!validStatus) {
            throw new BusinessException("状态值不合法，只能是 "
                    + DbSourceStatus.ENABLED + "（启用）或 "
                    + DbSourceStatus.DISABLED + "（停用）");
        }

        DbSource dbSource = getByIdAndUserId(userId, id);
        dbSource.setStatus(status);
        updateById(dbSource);
        // 停用 / 启用同样要广播：本实例的缓存在 Redis 里是共享的，
        // 删掉之后别的实例读到的就是新状态；但连接池是本进程的，必须靠广播清
        invalidator.invalidateEverywhere(id);
    }

    private boolean checkNameUniqueExcludeId(Long userId, String name, Long excludeId) {
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId, userId)
               .eq(DbSource::getName, name)
               .ne(DbSource::getId, excludeId);
        return count(wrapper) == 0;
    }

    private void deleteCacheByKey(String cacheKey) {
        invalidator.deleteCacheQuietly(cacheKey);
    }

    private DbSource toEntity(DbSourceDTO dto){
        DbSource dbSource = new DbSource();
        dbSource.setName(dto.getName());
        dbSource.setType(dto.getType());
        dbSource.setHost(dto.getHost());
        dbSource.setPort(dto.getPort());
        dbSource.setDatabaseName(dto.getDatabaseName());
        dbSource.setUsername(dto.getUsername());
        // ⚠️ 刻意**不在这里**设置密码字段：密码要么来自 encrypt(...)，要么来自库里已有的密文，
        //    统一由调用方（add / update）显式赋值。以前这里放过 dto.getPassword()（明文），
        //    一旦哪个调用方忘了覆盖，明文就会直接落库。
        return dbSource;
    }

    private DbSourceVO toVO(DbSource dbSource) {
        DbSourceVO vo = new DbSourceVO();
        vo.setId(dbSource.getId());
        vo.setName(dbSource.getName());
        vo.setType(dbSource.getType());
        vo.setHost(dbSource.getHost());
        vo.setPort(dbSource.getPort());
        vo.setDatabaseName(dbSource.getDatabaseName());
        vo.setUsername(dbSource.getUsername());
        vo.setStatus(dbSource.getStatus());
        vo.setCreatedAt(dbSource.getCreatedAt());
        vo.setUpdatedAt(dbSource.getUpdatedAt());
        return vo;
    }
}
