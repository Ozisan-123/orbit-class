package com.orbit.scheduling.security;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** {@code users} 的**只读** Mapper（scheduling 只做鉴权）。 */
@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUser> {
}
