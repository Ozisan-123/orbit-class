package com.orbit.academic.subject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code subjects} 表实体。
 *
 * <p>唯一约束：{@code uq_subject_org_name (organization_id, name)} ——
 * 同一学校内科目名不能重复（跨学校可以重名）。
 */
@TableName("subjects")
public class Subject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String name;
    /** {@code major}（主科）/ {@code minor}（副科）等；Python 侧默认 {@code "minor"}。 */
    private String category;

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
