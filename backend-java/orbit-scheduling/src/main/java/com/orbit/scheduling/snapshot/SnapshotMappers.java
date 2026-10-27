package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 只读 Ref 的 Mapper 集合（放在一个文件里 —— 它们只是同一件事的多个门面，
 * 每个都是空的 {@code BaseMapper}）。
 *
 * <p>都在 {@code scheduling.snapshot} 包下，对应 README 包归属里的
 * 「**冻结并消费**排课输入快照」。
 */
public final class SnapshotMappers {

    private SnapshotMappers() {
    }

    @Mapper
    public interface ClassRefMapper extends BaseMapper<ClassRef> {
    }

    @Mapper
    public interface GradeRefMapper extends BaseMapper<GradeRef> {
    }

    @Mapper
    public interface TeacherRefMapper extends BaseMapper<TeacherRef> {
    }

    @Mapper
    public interface SubjectRefMapper extends BaseMapper<SubjectRef> {
    }

    @Mapper
    public interface GradeSubjectRefMapper extends BaseMapper<GradeSubjectRef> {
    }

    @Mapper
    public interface AssignmentRefMapper extends BaseMapper<AssignmentRef> {
    }

    @Mapper
    public interface GradeFixedSlotRefMapper extends BaseMapper<GradeFixedSlotRef> {
    }
}
