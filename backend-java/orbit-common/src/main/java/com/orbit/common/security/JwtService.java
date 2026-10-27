package com.orbit.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 标准 JWT（HS256）的签发与校验。
 *
 * <h2>关于与 Python 的 token 不通用（这是有意的）</h2>
 * Python 侧签的**不是**标准 JWT，而是自己拼的格式：
 * <pre>{@code
 * base64url(json{"uid":..,"exp":..,"nonce":..}) + "." + base64url(HMAC-SHA256(SECRET, body))
 * }</pre>
 * 没有 header、没有标准 claim。两边 token 不通用，意味着**灰度切换时老 token 失效、
 * 用户要重新登录一次**。
 *
 * <p>选择标准 JWT 的理由：token 对前端是**不透明**的（只往 localStorage 存、原样回传），
 * 所以格式**不属于契约**；而标准 JWT 在接网关、接 OAuth、用现成中间件时都省事。
 *
 * <h2>密钥长度</h2>
 * HS256 要求密钥至少 256 bit（32 字节）。{@code APP_SECRET_KEY} 实测 49 字节 ✓ 够用。
 * 短于 32 字节时**直接启动失败**而不是偷偷派生 —— 弱密钥是安全问题，
 * 静默"修好"会让人以为配置没问题。
 */
public class JwtService {

    /** HS256 的最小密钥长度（字节）。 */
    public static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(String secret, long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("orbit.jwt.secret 未配置（应来自 APP_SECRET_KEY）");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "orbit.jwt.secret 太短：" + bytes.length + " 字节，HS256 至少需要 "
                            + MIN_SECRET_BYTES + " 字节。请把 APP_SECRET_KEY 换成长随机串。");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 签发。{@code nonce} 对应 Python 的 nonce 字段，标准 JWT 里用 {@code jti}。 */
    public String issue(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并取出用户 id。
     *
     * <p>失败一律返回 {@link Optional#empty()}，**不抛异常** ——
     * 调用方（过滤器）要根据结果返回 401，而不是让异常冒到 500。
     */
    public Optional<Long> userIdOf(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不对 / 过期 / 格式非法 / subject 不是数字 —— 都当作未认证
            return Optional.empty();
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
