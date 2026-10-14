package com.example.sqlquery.infra;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sqlquery.cache.DbSourceCacheInvalidator;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.mapper.DbSourceMapper;
import com.example.sqlquery.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把存量数据源密码重新加密到<b>当前</b>密钥版本。
 *
 * <h2>它补上的是"轮换"的最后一段</h2>
 *
 * <p>密钥版本化让"老数据仍然解得开"，但那只解决了<b>读</b>的问题 ——
 * 库里那些用旧密钥加密的记录会一直留着，密钥也就一直不能真正作废。
 * 这个 Runner 负责把它们换成新密钥，之后旧密钥才可以安全地丢掉。
 *
 * <h2>为什么是"启动时手动触发"而不是定时任务</h2>
 *
 * <p>重新加密会<b>改写所有数据源密码</b>。这是一个有破坏性的批量操作，
 * 应该由人决定什么时候做、并且能看着日志确认结果，而不是某天凌晨自己跑起来。
 * 所以它默认关闭，靠 {@code aes.reencrypt-on-startup=true} 显式打开。
 *
 * <h2>幂等性</h2>
 *
 * <p>按"密文里的版本号是不是当前版本"判断，已经是当前版本的直接跳过。
 * 所以<b>跑第二遍什么都不会做</b>，命令可以安全地重复执行。
 *
 * <h2>失败处理</h2>
 *
 * <p>单条失败只记录并跳过，<b>不中断整批</b>。理由：常见失败是"这条记录用的是
 * 某个已经没有配置的密钥版本"，那是数据问题，其它记录不该被它连累。
 * 失败的条数会汇总在结尾的日志里 —— 这个数字必须被看到，因为
 * <b>"迁移完成"和"全部成功"不是一回事</b>。
 *
 * <h2>⚠️ 必须同时清缓存</h2>
 *
 * <p>数据源元数据缓存在 Redis 里，缓存的值<b>包含加密后的密码</b>。
 * 只改数据库不清缓存的话，接下来一段时间业务读到的还是旧密文 ——
 * 而那个旧密文对应的旧密钥可能已经被删了，表现就是"刚迁移完，连接反而全挂了"。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aes.reencrypt-on-startup", havingValue = "true")
public class PasswordReEncryptRunner implements ApplicationRunner {

    private final DbSourceMapper dbSourceMapper;
    private final AesUtil aesUtil;
    private final DbSourceCacheInvalidator invalidator;

    public PasswordReEncryptRunner(DbSourceMapper dbSourceMapper,
                                   AesUtil aesUtil,
                                   DbSourceCacheInvalidator invalidator) {
        this.dbSourceMapper = dbSourceMapper;
        this.aesUtil = aesUtil;
        this.invalidator = invalidator;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DbSource> all = dbSourceMapper.selectList(null);

        int migrated = 0;
        int skipped = 0;
        int failed = 0;

        for (DbSource dbSource : all) {
            String cipherText = dbSource.getPasswordEncrypted();

            if (cipherText == null || cipherText.isBlank()) {
                skipped++;
                continue;
            }
            if (aesUtil.isEncryptedWithCurrentVersion(cipherText)) {
                skipped++;
                continue;
            }

            String fromVersion = aesUtil.extractVersion(cipherText);
            try {
                // 用旧版本解出来、再用当前版本加密 —— 明文只在内存里停留这一步
                String reEncrypted = aesUtil.encrypt(aesUtil.decrypt(cipherText));

                int affected = dbSourceMapper.update(null, new LambdaUpdateWrapper<DbSource>()
                        .eq(DbSource::getId, dbSource.getId())
                        .set(DbSource::getPasswordEncrypted, reEncrypted));

                if (affected == 1) {
                    // 缓存里存的是旧密文，必须一起失效，否则迁移完业务反而连不上
                    invalidator.invalidateEverywhere(dbSource.getId());
                    migrated++;
                } else {
                    // 条件更新影响 0 行：记录在处理过程中被删了
                    log.warn("数据源在处理过程中已不存在，跳过。dataSourceId={}", dbSource.getId());
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                // 注意：日志里只出现"数据源 id"和"旧版本号"，不出现密文与密钥
                log.error("数据源密码重新加密失败，已跳过。dataSourceId={}, 原密钥版本={}",
                        dbSource.getId(), fromVersion == null ? "(历史 ECB)" : fromVersion, e);
            }
        }

        log.info("数据源密码重新加密完成：共 {} 条，已迁移 {} 条，跳过 {} 条（已是当前版本），失败 {} 条。当前密钥版本={}",
                all.size(), migrated, skipped, failed, aesUtil.getCurrentKeyVersion());
    }
}
