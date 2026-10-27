package com.orbit.academic.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码编码器的**跨语言兼容性**测试。
 *
 * <p>这是 P1 里最不能出错的一段：{@code users.password_hash} 里已经有 Python 生成的哈希，
 * Java 校验不了它们就意味着**迁移后所有老用户都登录不了**。
 *
 * <p>下面的向量是用 Python 侧的真实代码算出来的（{@code backend/auth.py} 的
 * {@code hash_password} / {@code verify_password}），不是手写猜的：
 * <pre>
 * ITERATIONS = 310000
 * 口令       = Admin@123
 * 盐         = 00112233445566778899aabbccddeeff（固定盐，便于写死断言）
 * digest     = b847df492012ef6ab8abce85a17925ceb741049026d6d1f12a9757fe4df5ee3b
 * Python verify_password 自检 = True / 错误口令 = False
 * </pre>
 */
class PythonPbkdf2PasswordEncoderTest {

    private static final String PASSWORD = "Admin@123";

    /** Python 生成的真实向量（固定盐，用于断言 Java 算出的摘要逐字符相同）。 */
    private static final String PYTHON_VECTOR =
            "pbkdf2_sha256$310000$00112233445566778899aabbccddeeff$"
                    + "b847df492012ef6ab8abce85a17925ceb741049026d6d1f12a9757fe4df5ee3b";

    /** Python 用**随机盐**生成的另一条真实哈希，用来确认随机盐那部分也对得上。 */
    private static final String PYTHON_RANDOM_SALT_VECTOR =
            "pbkdf2_sha256$310000$af56c97065d92bce18d0f38f80681e3d$"
                    + "e84a9cd656d20e0015c178a3a305321b7b880b92d854fee3ea562ef004b87b7c";

    private final PythonPbkdf2PasswordEncoder encoder = new PythonPbkdf2PasswordEncoder();

    @Test
    @DisplayName("能校验 Python 生成的哈希（迁移后老用户才能登录）")
    void verifiesPythonGeneratedHash() {
        assertTrue(encoder.matches(PASSWORD, PYTHON_VECTOR),
                "Java 必须能校验 Python 生成的哈希 —— 否则迁移后老用户全部登录不了");
        assertTrue(encoder.matches(PASSWORD, PYTHON_RANDOM_SALT_VECTOR),
                "随机盐的那条也要能校验");
    }

    @Test
    @DisplayName("错误口令不通过")
    void rejectsWrongPassword() {
        assertFalse(encoder.matches("wrong-password", PYTHON_VECTOR));
        assertFalse(encoder.matches("Admin@124", PYTHON_VECTOR));
        assertFalse(encoder.matches("", PYTHON_VECTOR));
        assertFalse(encoder.matches(null, PYTHON_VECTOR));
    }

    @Test
    @DisplayName("自产的哈希：格式与 Python 一致，且能自校验")
    void producesCompatibleFormat() {
        String encoded = encoder.encode(PASSWORD);

        String[] parts = encoded.split("\\$");
        assertEquals(4, parts.length, "必须是 4 段: 算法$轮数$盐$摘要");
        assertEquals("pbkdf2_sha256", parts[0]);
        assertEquals("310000", parts[1]);
        assertEquals(32, parts[2].length(), "16 字节盐 → 32 个 hex 字符");
        assertEquals(64, parts[3].length(), "32 字节摘要 → 64 个 hex 字符");

        assertTrue(encoder.matches(PASSWORD, encoded), "自产哈希必须能自校验");
        assertFalse(encoder.matches("other-password", encoded));
    }

    @Test
    @DisplayName("每次编码的盐都不同（同一个口令两次哈希不相等）")
    void saltIsRandom() {
        String first = encoder.encode(PASSWORD);
        String second = encoder.encode(PASSWORD);
        assertNotEquals(first, second, "盐必须随机，否则相同口令会产生相同哈希");
        assertTrue(encoder.matches(PASSWORD, first));
        assertTrue(encoder.matches(PASSWORD, second));
    }

    @Test
    @DisplayName("摘要与 Python 逐字符相同（同盐同口令）")
    void digestMatchesPython() {
        // 用 Python 那条向量的盐与轮数，验证 Java 算出的摘要完全一致
        String hex = PythonPbkdf2PasswordEncoder.digestHex(
                PASSWORD,
                java.util.HexFormat.of().parseHex("00112233445566778899aabbccddeeff"),
                PythonPbkdf2PasswordEncoder.ITERATIONS);

        assertEquals("b847df492012ef6ab8abce85a17925ceb741049026d6d1f12a9757fe4df5ee3b", hex,
                "摘要必须与 Python 的 hashlib.pbkdf2_hmac 逐字符相同");
    }

    @Test
    @DisplayName("口令短于 8 位时拒绝编码（与 Python 的 ValueError 一致）")
    void rejectsShortPassword() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> encoder.encode("short"));
        assertEquals("密码至少需要 8 位", e.getMessage());
    }

    @Test
    @DisplayName("非法哈希格式返回 false，而不是抛异常")
    void malformedHashReturnsFalse() {
        assertFalse(encoder.matches(PASSWORD, "not-a-hash"));
        assertFalse(encoder.matches(PASSWORD, ""));
        assertFalse(encoder.matches(PASSWORD, "pbkdf2_sha256$abc$00$00"), "轮数不是数字");
        assertFalse(encoder.matches(PASSWORD, "bcrypt$12$xxxx"), "算法不是 pbkdf2_sha256");
        assertFalse(encoder.matches(PASSWORD, "pbkdf2_sha256$310000$zz$zz"), "盐不是合法 hex");
        // 一个坏行不能让整个登录接口 500
    }

    @Test
    @DisplayName("UTF-8 口令（中文/emoji）也能校验")
    void supportsNonAsciiPassword() {
        String encoded = encoder.encode("密码测试12345");
        assertTrue(encoder.matches("密码测试12345", encoded));
        assertFalse(encoder.matches("错误密码12345", encoded));
    }
}
