package com.orbit.academic.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code organizations} 表的 Mapper。 */
@Mapper
public interface OrganizationMapper extends BaseMapper<Organization> {

    /**
     * 对本组织加**行级写锁**（{@code SELECT ... FOR UPDATE}），用来把同一租户的并发操作串行化。
     *
     * <p>对应 Python 的：
     * <pre>{@code
     * db.query(Organization).filter(Organization.id == user.organization_id).with_for_update().one()
     * }</pre>
     * 自动分配教师、批量设置教师班级这两个接口都先拿这把锁 —— 否则两个管理员同时提交，
     * 会各自基于**过期**的分配情况做决策，产生重叠或超额的分配。
     *
     * <p>⚠ 锁的是 {@code organizations} 这一行（租户维度），不是具体业务表 ——
     * 这是"用一个稳定的对象把租户内的操作串行化"的常见手法，
     * 也意味着**这两个接口在同一学校内是串行的**（不同学校互不影响）。
     * 因为走的是同一个事务，锁在事务提交/回滚时自动释放。
     */
    @Select("SELECT id FROM organizations WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}
