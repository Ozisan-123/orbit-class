package com.orbit.academic.clazz;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code classes} 表实体 —— 一个「班级」。
 *
 * <p>类名用 {@code Clazz} 是因为 {@code class} 是 Java 关键字
 * （包名 {@code clazz} 也是这个原因，与 README 的包归属一致）。
 *
 * <p>唯一约束 {@code uq_class_org_name (organization_id, name)}：同一学校内班级名唯一。
 * 注意它**不是** (grade_id, name) —— 所以「一年级1班」和「二年级1班」不能同名。
 * 这是既有 schema 的行为，不要"顺手修正"。
 */
@TableName("classes")
public class Clazz {

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
