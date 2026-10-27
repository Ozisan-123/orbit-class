package com.orbit.scheduling.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 排课分布式锁（Redis）—— 对应 Python {@code redis_service.py:121-171}。
 *
 * <h2>锁的 key 与 Python 逐字一致</h2>
 * {@code orbit:schedule_lock:<organization_id>} —— 迁移期双跑时，
 * 从 Java 发起的排课与从 Python 发起的排课会**争同一把锁** ✓ 否则两边各排一次，
 * 同一学校就会同时跑两个求解。
 *
 * <h2>三个动作都是原子的</h2>
 * <ul>
 *   <li><b>抢</b>：{@code SET key token NX EX ttl} —— 一条命令完成"不存在才设置 + 带过期"</li>
 *   <li><b>释放</b>：Lua 脚本先比对 token 再删 —— **不能直接 DEL**，
 *       否则一个超时后刚把锁续上的旧任务会把新持有者的锁删掉</li>
 *   <li><b>续租</b>：Lua 脚本先比对 token 再 EXPIRE</li>
 * </ul>
 * 比对 token 这两步是这段代码的核心价值。直接 {@code DEL} 是分布式锁最经典的 bug：
 * 看起来能跑，只有在"任务超时 + 另一个任务刚好抢到"时才会出事。
 *
 * <h2>Redis 不可用时的降级</h2>
 * 与 Python 一致：{@code acquired = !redisEnabled || token != null}。
 * 也就是"Redis 不可用 → 当作拿到锁"（可用性优先）。
 * 真正的兜底是容量槽位（数据库里的租约），它不依赖 Redis。
 */
@Service
public class ScheduleLockService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleLockService.class);
    private static final String KEY_PREFIX = "orbit:schedule_lock:";

    /** 比对 token 后删除（对应 Python 的 release 脚本）。 */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    /** 比对 token 后续期（对应 Python 的 renew 脚本）。 */
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('expire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int lockSeconds;
    private final SecureRandom random = new SecureRandom();

    public ScheduleLockService(StringRedisTemplate redis,
                               @Value("${orbit.scheduling.lock-seconds:300}") int lockSeconds) {
        this.redis = redis;
        this.lockSeconds = lockSeconds;
    }

    /**
     * 抢锁。
     *
     * @return {@link LockAttempt}；{@link LockAttempt#acquired()} 为 false 表示**别的排课任务在跑**
     *         （接口要回 409）
     */
    public LockAttempt acquire(long organizationId) {
        String token = newToken();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    key(organizationId), token, Duration.ofSeconds(lockSeconds));
            // ok == null 表示在 pipeline/事务里没拿到结果，按失败处理（保守）
            return new LockAttempt(true, Boolean.TRUE.equals(ok) ? token : null);
        } catch (Exception e) {
            // Redis 挂了 → 降级为"没有锁"，让容量槽位去兜底
            log.warn("获取排课锁失败，降级为无锁执行: {}", e.getMessage());
            return new LockAttempt(false, null);
        }
    }

    /** 释放（只有持有者能释放）。 */
    public void release(long organizationId, String token) {
        if (token == null) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key(organizationId)), token);
        } catch (Exception e) {
            // Python 这里也是 pass：释放失败不该影响业务流程
            log.warn("释放排课锁出错: {}", e.getMessage());
        }
    }

    /** 续租（只有持有者能续）。 */
    public boolean renew(long organizationId, String token) {
        if (token == null) {
            return true;      // 与 Python 一致：无 token（降级模式）视为成功
        }
        try {
            Long result = redis.execute(RENEW_SCRIPT, List.of(key(organizationId)),
                    token, String.valueOf(lockSeconds));
            return result != null && result > 0;
        } catch (Exception e) {
            log.warn("续租排课锁出错: {}", e.getMessage());
            return false;
        }
    }

    private static String key(long organizationId) {
        return KEY_PREFIX + organizationId;
    }

    /** 对应 Python 的 {@code secrets.token_urlsafe(24)}。 */
    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 抢锁结果。
     *
     * <p>⚠ {@link #acquired()} 的语义是**"可以继续"**而不是"真的拿到锁"：
     * Redis 不可用时 {@code redisEnabled=false} 也返回 true（降级放行）。
     * 这个命名来自 Python 的 {@code LockAttempt.acquired}，保持一致以免误读。
     */
    public record LockAttempt(boolean redisEnabled, String token) {

        public boolean acquired() {
            return !redisEnabled || token != null;
        }
    }
}
