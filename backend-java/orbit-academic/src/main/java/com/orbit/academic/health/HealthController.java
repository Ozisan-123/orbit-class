package com.orbit.academic.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * {@code GET /api/health} —— P0 骨架的验证点，也是「一条命令能不能跑通」的证据。
 *
 * <h2>契约（§9 必须保持）</h2>
 * Python 原版返回：
 * <pre>{@code
 * {"ok": true, "redis": {"available": true, "used_memory": 1048576, "used_memory_human": "1.00M"}}
 * }</pre>
 * 这里<b>逐字保留</b> {@code ok} 与 {@code redis} 两个字段（含 {@code redis} 的子字段结构），
 * 另外<b>追加</b> {@code app / db / flyway} 三个字段。追加是安全的：
 * 前端只读它认识的键，不会因为多出字段而失败。
 *
 * <p>{@code ok} 的语义与 Python 版一致 —— 「进程活着」就是 true。依赖项的可用性放在
 * 各自的子对象里报告，不把 {@code ok} 变成整个依赖链的与运算。这样 health 才始终可用，
 * 不会因为 Redis 挂了就返回 500（那会同时打掉监控和排障）。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;
    private final ObjectProvider<Flyway> flyway;
    private final String appName;
    private final String appVersion;

    public HealthController(DataSource dataSource,
                            ObjectProvider<StringRedisTemplate> redisTemplate,
                            ObjectProvider<Flyway> flyway,
                            @Value("${spring.application.name:orbit}") String appName,
                            @Value("${orbit.version:0.1.0-SNAPSHOT}") String appVersion) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.flyway = flyway;
        this.appName = appName;
        this.appVersion = appVersion;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("app", appName);
        body.put("version", appVersion);
        body.put("redis", redisStatus());
        body.put("db", dbStatus());
        body.put("flyway", flywayStatus());
        return body;
    }

    /** 与 Python 的 {@code redis_status()} 同构：available / used_memory / used_memory_human。 */
    private Map<String, Object> redisStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            out.put("available", false);
            return out;
        }
        try {
            Properties info = template.execute(
                    (RedisConnection connection) -> connection.serverCommands().info("memory"));
            if (info == null) {
                out.put("available", false);
                return out;
            }
            out.put("available", true);
            out.put("used_memory", parseLong(info.getProperty("used_memory")));
            out.put("used_memory_human", info.getProperty("used_memory_human", "0B"));
        } catch (Exception e) {
            log.warn("Redis 健康检查失败: {}", e.getMessage());
            out.put("available", false);
        }
        return out;
    }

    private Map<String, Object> dbStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            out.put("available", true);
            out.put("product", meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
            out.put("catalog", connection.getCatalog());
        } catch (Exception e) {
            log.warn("数据库健康检查失败: {}", e.getMessage());
            out.put("available", false);
        }
        return out;
    }

    /** 报告当前基线/迁移版本 —— P0 阶段用它证明 Flyway 真的接上了。 */
    private Map<String, Object> flywayStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        Flyway f = flyway.getIfAvailable();
        if (f == null) {
            out.put("available", false);
            return out;
        }
        try {
            MigrationInfo current = f.info().current();
            out.put("available", true);
            out.put("version", current == null ? null : String.valueOf(current.getVersion()));
            out.put("description", current == null ? null : current.getDescription());
            out.put("pending", f.info().pending().length);
        } catch (Exception e) {
            log.warn("Flyway 状态检查失败: {}", e.getMessage());
            out.put("available", false);
        }
        return out;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
