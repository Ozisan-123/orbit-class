package com.orbit.common.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 时间工具 —— 统一「库里存的是哪种时间」这件事。
 *
 * <h2>为什么需要它</h2>
 * Python 侧的模型全部写成：
 * <pre>{@code
 * created_at = Column(DateTime(timezone=True), nullable=False, default=utcnow)
 * }</pre>
 * 注意 {@code utcnow} —— 写入的是**朴素 UTC**（没有时区信息，但值按 UTC 算）。
 *
 * <p>Java 里如果顺手写 {@code LocalDateTime.now()}，拿到的是**本地时间（东八区）**，
 * 与 Python 差 8 小时。这类错误不会报错、不会崩，
 * 只会在「按时间排序」「显示创建时间」时表现得很怪 —— 属于最难查的那一类。
 *
 * <h2>实测确认</h2>
 * 用 {@code NOW()} 插入一行，Python 与 Java 两边读到的都是
 * {@code "2026-09-27T06:10:59"}（而当时本地时间是 14:10）——
 * 说明 MySQL 容器跑在 UTC，两边读取一致。**写入端也必须用 UTC 才一致。**
 *
 * <p>⚠ 这只是一个**临时对齐**措施。真正干净的做法是全程用带时区的
 * {@code Instant} / {@code OffsetDateTime}，并在 JDBC 与 JSON 两层显式声明时区。
 * 那是一次涉及全部时间字段的改造，已记入 TODO，不在 P1 范围。
 */
public final class UtcTimes {

    private UtcTimes() {
    }

    /**
     * 当前 UTC 时间（朴素，**截断到秒**）。
     *
     * <p>为什么要截断：MySQL 的 {@code datetime}（无精度声明）只存到秒。
     * 而 {@code LocalDateTime.now()} 带纳秒 —— 直接拿去 insert 再返回给客户端，
     * 会出现**同一个接口两次调用格式不一致**：
     * <pre>
     * POST /api/organizations → "2026-09-27T06:23:11.0585571"   ← 内存对象，带纳秒
     * GET  /api/organizations → "2026-09-27T06:23:11"           ← 从库里读回，只有秒
     * </pre>
     * Python 侧不会有这个问题，因为它在 commit 后调了 {@code db.refresh(organization)}
     * 重新读库。这里选择**在写入前就截断**，比"再查一次库"更简单，也没有额外往返。
     *
     * <p>（实测踩过：POST 的响应带 7 位小数，而 Python 是干净的秒。）
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
