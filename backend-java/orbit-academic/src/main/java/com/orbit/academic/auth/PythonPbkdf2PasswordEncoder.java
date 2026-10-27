package com.orbit.academic.auth;

import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * 与 Python 侧 {@code backend/auth.py} 完全兼容的密码编码器。
 *
 * <h2>为什么不能用 Spring Security 现成的编码器</h2>
 * 数据库 {@code users.password_hash} 里已经有**现成的哈希**，格式是
 * Python 自己定的：
 * <pre>{@code
 * pbkdf2_sha256$310000$<salt_hex>$<digest_hex>
 * }</pre>
 * 如果 Java 用别的编码器（BCrypt、或者 Spring 的 {@code Pbkdf2PasswordEncoder}
 * 默认格式 {@code {pbkdf2@SpringSecurity_v5_8}...}），
 * **迁移后所有老用户都登录不了** —— 因为校验函数对不上已有的哈希。
 *
 * <p>所以这里逐项对齐 Python 的实现：
 * <table>
 *   <tr><th>项</th><th>Python</th><th>这里</th></tr>
 *   <tr><td>算法</td><td>{@code hashlib.pbkdf2_hmac("sha256", ...)}</td><td>{@code PBKDF2WithHmacSHA256}</td></tr>
 *   <tr><td>轮数</td><td>{@code 310_000}</td><td>同（<b>必须</b>同，改了就校验不了旧哈希）</td></tr>
 *   <tr><td>盐</td><td>{@code secrets.token_bytes(16)} → 16 字节 → hex</td><td>{@code SecureRandom} 16 字节 → hex</td></tr>
 *   <tr><td>摘要</td><td>32 字节 → hex（64 字符）</td><td>同（PBKDF2-SHA256 固定 256 bit）</td></tr>
 *   <tr><td>口令编码</td><td>{@code password.encode()} 即 UTF-8</td><td>{@code UTF-8}</td></tr>
 *   <tr><td>比较</td><td>{@code hmac.compare_digest}（恒定时间）</td><td>{@code MessageDigest.isEqual}（恒定时间）</td></tr>
 *   <tr><td>最短长度</td><td>{@code len(password) < 8} → 抛 {@code ValueError("密码至少需要 8 位")}</td><td>同（抛 {@link IllegalArgumentException}）</td></tr>
 * </table>
 *
 * <p><b>为什么新哈希也要用同一个格式</b>：Java 改完密码后，Python 侧（迁移期还在跑）
 * 或者其他工具读这个库时，必须也能校验 —— 两边共用一个格式是最省事的选择。
 *
 * <p><b>校验失败不抛异常，只返回 false</b>：格式非法（比如库里的值是 BCrypt、
 * 或者被手工改坏了）时返回 false 而不是崩掉，否则一个坏行会让整个登录接口 500。
 */
public class PythonPbkdf2PasswordEncoder implements PasswordEncoder {

    /** 与 Python 的 {@code PBKDF2_ITERATIONS} 一致。改动它会导致**旧哈希全部校验失败**。 */
    public static final int ITERATIONS = 310_000;

    private static final String ALGORITHM = "pbkdf2_sha256";
    private static final String JCE_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int EXPECTED_PARTS = 4;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String encode(CharSequence rawPassword) {
        String password = rawPassword == null ? "" : rawPassword.toString();
        // 与 Python 的 hash_password 一样：短于 8 位直接拒绝（不生成弱口令哈希）
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少需要 8 位");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] digest = derive(password, salt, ITERATIONS);
        return ALGORITHM + "$" + ITERATIONS + "$" + HexFormat.of().formatHex(salt)
                + "$" + HexFormat.of().formatHex(digest);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", EXPECTED_PARTS);
        if (parts.length != EXPECTED_PARTS || !ALGORITHM.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = derive(rawPassword.toString(), salt, iterations);
            // 恒定时间比较，避免计时侧信道（对应 Python 的 hmac.compare_digest）
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException e) {
            return false;       // 格式非法 → 当作校验不过，不要抛
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(JCE_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 不可用（JCE 环境异常）", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 供测试/诊断用：按 Python 的口径算一次摘要的 hex。 */
    static String digestHex(String password, byte[] salt, int iterations) {
        return HexFormat.of().formatHex(derive(password, salt, iterations));
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
