package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * **只读**的 {@code classes} 映射（academic 属主）。
 *
 * <p>放在 {@code scheduling.snapshot} 包下 —— 按 README 的包归属，
 * 这个包的职责是「**冻结并消费**排课输入快照」。这些 Ref 类就是快照的最小形态：
 * 只映射排课需要的列（班级 → 年级、科目、教师的名字用于展示），不写、不管生命周期。
 *
 * <p>⚠ 与 {@code security.AuthUser} 同理：**读** academic 的表不违反铁律 2
 * （铁律 2 禁止的是跨服务**写**）。但方案 §3 铁律 3 要求「排课输入用快照」，
 * 所以 P3 拆分时会把这些 Ref 换成"一次性拉取并冻结的快照对象"，
 * 而不是每次现查 —— 那既解决跨库问题，也让求解期间的输入不被并发修改。
 *
 * <p>类名带 {@code Ref} 后缀是刻意的：一眼能看出"这是对别人数据的引用，不是我的实体"。
 */
@TableName("classes")
public class ClassRef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String name;
    private Long gradeId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getGradeId() {
        return gradeId;
    }

    public void setGradeId(Long gradeId) {
        this.gradeId = gradeId;
    }
}
