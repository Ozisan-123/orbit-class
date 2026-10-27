package com.orbit.scheduling.rule;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** {@code constraint_rules} 的 Mapper。 */
@Mapper
public interface ConstraintRuleMapper extends BaseMapper<ConstraintRuleEntity> {

    /**
     * 锁住某组织的规则行 —— 给"首次自动初始化"用。
     *
     * <p>为什么需要它：新组织的规则是**懒初始化**的（第一次访问时才写入默认规则）。
     * 如果两个请求同时第一次访问，两边都会看到"一条规则都没有"然后各插一份 →
     * 出现两份一模一样的默认规则。
     *
     * <p>这里靠 InnoDB 在 **REPEATABLE-READ** 下对索引范围加的 **gap lock**：
     * 该组织一行都没有时，这个查询会锁住 {@code organization_id} 索引上的那个空隙，
     * 使并发的插入阻塞到本事务结束。
     *
     * <p>⚠ 隔离级别是这个方案的前提，所以 docker-compose 里
     * {@code --transaction-isolation=REPEATABLE-READ} 是**显式写出来**的
     * （注释原话：条件 UPDATE 的裁决依赖 InnoDB 行锁，隔离级别属于"不能悄悄变"的东西）。
     *
     * <p>返回的 id 列表本身没有用途，要的只是这次加锁。
     */
    @Select("SELECT id FROM constraint_rules WHERE organization_id = #{organizationId} FOR UPDATE")
    List<Long> lockOrganizationRules(@Param("organizationId") long organizationId);

    /**
     * 带乐观锁的编辑：只有 {@code version} 还是调用方看到的那个值时才更新。
     *
     * <p>返回 0 表示"这条规则刚被别人改过" → 上层翻成 409。
     *
     * <p>⚠ 用**一条条件 UPDATE** 而不是"先查再改"：后者在并发下两个请求都会读到
     * 同一个旧 version 然后都通过检查（先查后改之间存在窗口）。
     * 这也是本项目用 MyBatis 而不是 JPA 的原因之一 —— 要看得到受影响行数。
     *
     * <p>⚠ 注解必须是 {@link Update} 而不是 {@code @Select}：
     * 用 {@code @Select} 标 UPDATE 时 MyBatis 当查询执行，拿不到 rowcount，
     * 会抛 {@code BindingException: attempted to return null from a method
     * with a primitive return type (int)}（踩坑总结 B7）。
     */
    @Update("""
            <script>
            UPDATE constraint_rules
               SET rule_type = #{entity.ruleType},
                   params = #{entity.params, typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},
                   hardness = #{entity.hardness},
                   weight = #{entity.weight},
                   scope = #{entity.scope},
                   scope_id = #{entity.scopeId},
                   description = #{entity.description},
                   version = version + 1,
                   updated_at = #{entity.updatedAt}
             WHERE id = #{entity.id}
               AND organization_id = #{entity.organizationId}
               AND version = #{expectedRevision}
            </script>
            """)
    int updateWithRevision(@Param("entity") ConstraintRuleEntity entity,
                           @Param("expectedRevision") int expectedRevision);

    /**
     * 启停开关（{@code PATCH}）。
     *
     * <p>它**也要走乐观锁**：列表上的一个开关看起来无害，但"我看到的这一行"
     * 与"数据库里现在那一行"可能已经不是同一件事了。
     *
     * <p>⚠ 刻意**不更新 params/hardness/weight** —— 开关不该顺手覆盖别人对参数的编辑。
     */
    @Update("""
            UPDATE constraint_rules
               SET enabled = #{enabled},
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND version = #{expectedRevision}
            """)
    int updateEnabledWithRevision(@Param("id") long id,
                                  @Param("organizationId") long organizationId,
                                  @Param("enabled") boolean enabled,
                                  @Param("updatedAt") java.time.LocalDateTime updatedAt,
                                  @Param("expectedRevision") int expectedRevision);

    /** 带乐观锁的删除 —— 同样不允许"删除别人刚改过的那一版"。 */
    @Update("""
            DELETE FROM constraint_rules
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND version = #{expectedRevision}
            """)
    int deleteWithRevision(@Param("id") long id,
                           @Param("organizationId") long organizationId,
                           @Param("expectedRevision") int expectedRevision);
}
