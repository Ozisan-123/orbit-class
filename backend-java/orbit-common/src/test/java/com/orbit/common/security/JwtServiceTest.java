package com.orbit.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtService} 的行为测试。
 *
 * <p>重点是「**校验失败一律返回 empty，不抛异常**」—— 过滤器要靠这个结果返回 401；
 * 如果解析失败抛异常冒到 ControllerAdvice，用户会看到 500 而不是「请先登录」。
 */
class JwtServiceTest {

    /** 54 字节，满足 HS256 的 ≥32 字节要求。 */
    private static final String SECRET = "orbit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String OTHER_SECRET = "another-secret-0123456789abcdefghijklmnopqrstuvwxyz";

    private final JwtService jwt = new JwtService(SECRET, 3600);

    @Test
    @DisplayName("签发与校验往返：能取回同一个用户 id")
    void roundTrip() {
        String token = jwt.issue(42L);
        Optional<Long> userId = jwt.userIdOf(token);
        assertTrue(userId.isPresent());
        assertEquals(42L, userId.get());
    }

    @Test
    @DisplayName("两次签发的 token 不同（jti 随机），但都能校验")
    void tokensAreUnique() {
        String first = jwt.issue(7L);
        String second = jwt.issue(7L);
        assertNotEquals(first, second, "每次都应有新的 jti，否则无法做吊销");
        assertEquals(7L, jwt.userIdOf(first).orElseThrow());
        assertEquals(7L, jwt.userIdOf(second).orElseThrow());
    }

    @Test
    @DisplayName("过期的 token 不通过")
    void expiredTokenRejected() {
        // ttl = -1 秒 → 签发出来就已经过期
        JwtService expired = new JwtService(SECRET, -1);
        String token = expired.issue(1L);
        assertTrue(expired.userIdOf(token).isEmpty(), "过期必须被拒");
    }

    @Test
    @DisplayName("换一个密钥就不通过（签名校验生效）")
    void wrongSecretRejected() {
        String token = jwt.issue(1L);
        JwtService other = new JwtService(OTHER_SECRET, 3600);
        assertTrue(other.userIdOf(token).isEmpty(), "不同密钥必须验不过");
    }

    @Test
    @DisplayName("被篡改的 payload 不通过")
    void tamperedTokenRejected() {
        String token = jwt.issue(1L);
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "标准 JWT 必须是 3 段（header.payload.signature）");

        // 换掉 payload 段（模拟改成别的 uid），签名就不匹配了
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "XY." + parts[2];
        assertTrue(jwt.userIdOf(tampered).isEmpty(), "篡改 payload 必须被拒");
    }

    @Test
    @DisplayName("各种垃圾输入都返回 empty，不抛异常")
    void garbageReturnsEmpty() {
        assertTrue(jwt.userIdOf(null).isEmpty());
        assertTrue(jwt.userIdOf("").isEmpty());
        assertTrue(jwt.userIdOf("   ").isEmpty());
        assertTrue(jwt.userIdOf("not-a-token").isEmpty());
        assertTrue(jwt.userIdOf("a.b.c").isEmpty());
        // Python 那边的自定义格式（两段）也应该被拒 —— 两边 token 不通用是有意的
        assertTrue(jwt.userIdOf("eyJ1aWQiOjF9.HqMuur2VXUWjEUwtk1oDdgPqAg9XQYhu3c548nWl6L0").isEmpty(),
                "Python 的两段式 token 不该被当成本项目的 JWT 接受");
    }

    @Test
    @DisplayName("密钥太短：启动即失败，而不是偷偷派生弱密钥")
    void shortSecretFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtService("too-short", 3600));
        assertTrue(e.getMessage().contains("太短"), e.getMessage());
        assertTrue(e.getMessage().contains("32"), "错误信息要告诉用户需要多少字节：" + e.getMessage());
    }

    @Test
    @DisplayName("密钥为空：启动即失败")
    void blankSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> new JwtService(null, 3600));
        assertThrows(IllegalStateException.class, () -> new JwtService("", 3600));
        assertThrows(IllegalStateException.class, () -> new JwtService("   ", 3600));
    }

    @Test
    @DisplayName("ttl 可读回（供登录响应或诊断用）")
    void exposesTtl() {
        assertEquals(3600L, jwt.ttlSeconds());
        assertEquals(28800L, new JwtService(SECRET, 28800).ttlSeconds(),
                "默认应能对齐 Python 的 TOKEN_TTL_SECONDS=28800");
    }
}
