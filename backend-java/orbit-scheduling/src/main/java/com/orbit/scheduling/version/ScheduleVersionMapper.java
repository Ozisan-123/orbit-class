package com.orbit.scheduling.version;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** {@code schedule_versions} 的 Mapper。 */
@Mapper
public interface ScheduleVersionMapper extends BaseMapper<ScheduleVersion> {

    /** 评分是派生快照，不改 revision / updated_at。 */
    @Update("UPDATE schedule_versions SET score = #{scoreJson} WHERE id = #{id} AND organization_id = #{organizationId}")
    int updateScore(@Param("organizationId") long organizationId,
                    @Param("id") long id,
                    @Param("scoreJson") String scoreJson);
}
