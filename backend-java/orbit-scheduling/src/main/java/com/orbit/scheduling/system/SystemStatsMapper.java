package com.orbit.scheduling.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 存储运维用的查询/维护语句 —— 对应 Python {@code routers/system.py}。
 *
 * <p>为什么用**手写 SQL** 而不是复用各领域的 Mapper：
 * 这个接口的作用就是"跨表看一眼存储状况"，需要的是**一张宽表式的汇总**，
 * 拆成十几个 Mapper 调用反而更啰嗦、还多十几次往返。
 * Python 那边也是一个循环查了 13 张表。
 *
 * <p>⚠ 所有表名都来自**代码里的固定列表**（{@link SystemService#COUNTED_TABLES}），
 * 绝不接受用户输入 —— {@code OPTIMIZE TABLE} 的表名无法用占位符参数化
 * （它是 DDL，不是值），所以只能拼字符串，那就必须保证拼进去的是白名单里的常量。
 */
@Mapper
public interface SystemStatsMapper {

    /** 库的总大小（数据 + 索引），单位字节。 */
    @Select("""
            SELECT COALESCE(SUM(data_length + index_length), 0)
              FROM information_schema.tables
             WHERE table_schema = #{schema}
            """)
    long databaseSizeBytes(@Param("schema") String schema);

    /**
     * 本组织在各表里的行数。
     *
     * <p>用一条 {@code UNION ALL} 拿全部表 —— 比循环 13 次查询少 12 次往返，
     * 而且拿到的快照更一致（循环查的话，中间有写入就会得到互相矛盾的数字）。
     */
    @Select("""
            SELECT 'users' AS table_name, COUNT(*) AS row_count FROM users WHERE organization_id = #{orgId}
            UNION ALL SELECT 'grades', COUNT(*) FROM grades WHERE organization_id = #{orgId}
            UNION ALL SELECT 'classes', COUNT(*) FROM classes WHERE organization_id = #{orgId}
            UNION ALL SELECT 'subjects', COUNT(*) FROM subjects WHERE organization_id = #{orgId}
            UNION ALL SELECT 'teachers', COUNT(*) FROM teachers WHERE organization_id = #{orgId}
            UNION ALL SELECT 'grade_subjects', COUNT(*) FROM grade_subjects WHERE organization_id = #{orgId}
            UNION ALL SELECT 'teacher_assignments', COUNT(*) FROM teacher_assignments WHERE organization_id = #{orgId}
            UNION ALL SELECT 'fixed_slots', COUNT(*) FROM fixed_slots WHERE organization_id = #{orgId}
            UNION ALL SELECT 'grade_fixed_slots', COUNT(*) FROM grade_fixed_slots WHERE organization_id = #{orgId}
            UNION ALL SELECT 'schedules', COUNT(*) FROM schedules WHERE organization_id = #{orgId}
            UNION ALL SELECT 'schedule_versions', COUNT(*) FROM schedule_versions WHERE organization_id = #{orgId}
            UNION ALL SELECT 'schedule_version_entries', COUNT(*) FROM schedule_version_entries WHERE organization_id = #{orgId}
            UNION ALL SELECT 'schedule_audit_logs', COUNT(*) FROM schedule_audit_logs WHERE organization_id = #{orgId}
            """)
    List<Map<String, Object>> tableCounts(@Param("orgId") long organizationId);

    /**
     * 整理单张表的空间（MySQL 的 {@code OPTIMIZE TABLE}）。
     *
     * <p>⚠ 表名用 {@code ${}} 拼接是没办法的事（DDL 标识符不能参数化）。
     * 调用方**必须**只传 {@link SystemService#OPTIMIZABLE_TABLES} 里的常量。
     */
    @Update("OPTIMIZE TABLE `${tableName}`")
    void optimizeTable(@Param("tableName") String tableName);
}
