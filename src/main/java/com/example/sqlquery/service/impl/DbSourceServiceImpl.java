package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.DbSourceMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.AesUtil;
import com.example.sqlquery.util.JdbcUrlUtil;
import com.example.sqlquery.vo.DbSourceVO;
import com.example.sqlquery.util.ConnectionManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DbSourceServiceImpl extends ServiceImpl<DbSourceMapper, DbSource> implements DbSourceService {

    private final AesUtil aesUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConnectionManager connectionManager;

    private static final String CACHE_PREFIX = "datasource:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    public DbSourceServiceImpl(AesUtil aesUtil, StringRedisTemplate redisTemplate, ObjectMapper objectMapper, ConnectionManager connectionManager) {
        this.aesUtil = aesUtil;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.connectionManager = connectionManager;
    }

    @Override
    public void addDbSource(Long userId, DbSourceDTO dto) {
        if (!checkNameUnique(userId, dto.getName())) {
            throw new BusinessException("数据源名称已存在");
        }
        DbSource dbSource = toEntity(dto);
        dbSource.setUserId(userId);
        dbSource.setStatus(1);
        dbSource.setPasswordEncrypted(aesUtil.encrypt(dto.getPasswordEncrypted()));
        save(dbSource);
    }

    @Override
    public List<DbSourceVO> listByUserId(Long userId) {
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId,userId);
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
        dbSource.setPasswordEncrypted(aesUtil.encrypt(dto.getPasswordEncrypted()));
        updateById(dbSource);
        deleteCache(id);
        connectionManager.evict(id);
    }

    @Override
    public void deleteDbSource(Long userId, Long id) {
        DbSource exist = getById(id);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BusinessException("数据源不存在");
        }
        removeById(id);
        deleteCache(id);
        connectionManager.evict(id);
    }

    @Override
    public boolean testConnection(DbSourceDTO dto) {
        DbSource dbSource =toEntity(dto);
        String url = JdbcUrlUtil.build(dbSource);
        try(Connection connection = DriverManager.getConnection(
                url,
                dbSource.getUsername(),
                dto.getPasswordEncrypted())) {
            return  true;
        }catch (SQLException e){
            throw new BusinessException("无法连接到数据库：" + e.getMessage());
        }
    }

    @Override
    public IPage<DbSourceVO> pageByUserId(Long userId, long current, long size) {
        Page<DbSource> page = new Page<>(current, size);
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId, userId)
               .orderByDesc(DbSource::getCreatedAt);

        Page<DbSource> result = page(page, wrapper);

        Page<DbSourceVO> voPage = new Page<>(current, size, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public DbSource getByIdAndUserId(Long userId, Long id) {
        String cacheKey = CACHE_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, DbSource.class);
            } catch (Exception e) {
                throw new BusinessException("缓存数据异常");
            }
        }

        DbSource dbSource = getById(id);
        if (dbSource == null || !dbSource.getUserId().equals(userId)) {
            throw new BusinessException("数据源不存在");
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dbSource), CACHE_TTL);
        } catch (Exception e) {
            // 缓存写入失败不影响主流程
        }
        return dbSource;
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
        DbSource dbSource = getByIdAndUserId(userId, id);
        dbSource.setStatus(status);
        updateById(dbSource);
        deleteCache(id);
        connectionManager.evict(id);
    }

    private boolean checkNameUniqueExcludeId(Long userId, String name, Long excludeId) {
        LambdaQueryWrapper<DbSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbSource::getUserId, userId)
               .eq(DbSource::getName, name)
               .ne(DbSource::getId, excludeId);
        return count(wrapper) == 0;
    }

    private void deleteCache(Long id) {
        redisTemplate.delete(CACHE_PREFIX + id);
    }

    private DbSource toEntity(DbSourceDTO dto){
        DbSource dbSource = new DbSource();
        dbSource.setName(dto.getName());
        dbSource.setType(dto.getType());
        dbSource.setHost(dto.getHost());
        dbSource.setPort(dto.getPort());
        dbSource.setDatabaseName(dto.getDatabaseName());
        dbSource.setUsername(dto.getUsername());
        dbSource.setPasswordEncrypted(dto.getPasswordEncrypted());
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
