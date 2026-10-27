package com.orbit.academic.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 登录失败限流（对应 Python 的 {@code redis_service.py:70-108}）。
 *
 * <p>契约：连续失败 {@code LOGIN_MAX_FAILURES}（默认 5）次后，
 * 登录接口返回 <b>429</b> + {@code {"detail":"登录失败次数过多，请在 N 秒后重试"}}
 * 且带 {@code Retry-After} 响应头。窗口 {@code LOGIN_WINDOW_SECONDS}（默认 300 秒）。
 *
 * <h2>Redis key 与 Python 逐字一致（这是有意的）</h2>
 * <pre>{@code orbit:login_failures:<sha256(username.strip().lower() + "|" + client_ip)>}</pre>
 * 两边共用同一个 Redis，key 一致意味着**迁移期双跑的计数是共享的** ——
 * 攻击者在 Python 上试 3 次、在 Java 上试 3 次，总共算 6 次而不是各算 3 次。
 * 如果 key 不一致，限流在切换期间会被绕过。
 *
 * <h2>Redis 不可用时降级放行</h2>
 * Python 的每个函数都 {@code try/except} 后返回「不限流」。这里保持一致：
 * <b>限流失败不应该让登录功能整体不可用</b>（可用性优先于这个非核心的防护）。
 * 真正的约束是启动时的 {@code REDIS_REQUIRED=true}（见 redis_service 的
 * {@code verify_redis_configuration}）。
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);
    private static final String KEY_PREFIX = "orbit:login_failures:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final int windowSeconds;

    public LoginAttemptGuard(StringRedisTemplate redis,
                             @Value("${orbit.login.max-failures:5}") int maxFailures,
                             @Value("${orbit.login.window-seconds:300}") int windowSeconds) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.windowSeconds = windowSeconds;
    }

    /**
     * 还要等多少秒才能再试；0 表示可以试。
     *
     * <p>对应 Python 的 {@code login_retry_after}：
     * 失败数不足时返回 0；够了则返回 key 的剩余 TTL（**最小 1**，
     * 因为 TTL 可能恰好读到 -1/-2，返回 0 会让调用方以为不用等）。
     */
    public int retryAfterSeconds(String username, String clientIp) {
        try {
            String value = redis.opsForValue().get(key(username, clientIp));
            int failures = value == null ? 0 : Integer.parseInt(value.trim());
            if (failures < maxFailures) {
                return 0;
            }
            Long ttl = redis.getExpire(key(username, clientIp));
            return (ttl == null || ttl <= 0) ? 1 : ttl.intValue();
        } catch (Exception e) {
            log.warn("登录限流检查失败，按不限流处理: {}", e.getMessage());
            return 0;
        }
    }

    /** 记一次失败。INCR + EXPIRE（对应 Python 的 pipeline）。 */
    public void recordFailure(String username, String clientIp) {
        try {
            String key = key(username, clientIp);
            redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofSeconds(windowSeconds));
        } catch (Exception e) {
            log.warn("记录登录失败次数出错: {}", e.getMessage());
        }
    }

    /** 登录成功后清零。 */
    public void clear(String username, String clientIp) {
        try {
            redis.delete(key(username, clientIp));
        } catch (Exception e) {
            log.warn("清除登录失败次数出错: {}", e.getMessage());
        }
    }

    /** 与 Python 的 {@code _login_key} 逐字一致。 */
    private static String key(String username, String clientIp) {
        String identity = safe(username).strip().toLowerCase(Locale.ROOT) + "|" + safe(clientIp);
        return KEY_PREFIX + sha256Hex(identity);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
