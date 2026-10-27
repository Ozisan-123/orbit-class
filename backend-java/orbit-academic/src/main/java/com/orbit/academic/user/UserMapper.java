package com.orbit.academic.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code users} 表的 Mapper。
 *
 * <p>{@link BaseMapper} 已经提供 {@code selectById} / {@code insert} / {@code updateById} 等，
 * 常规 CRUD 不用自己写 SQL。
 *
 * <p>注意：**租户隔离不在这里做**。Python 侧的写法是每个查询都手工加
 * {@code filter(User.organization_id == user.organization_id)}，这里保持一致 ——
 * 由 Service 层显式加条件，而不是靠 MyBatis-Plus 的拦截器自动拼。
 * 理由：拦截器一旦被绕过（比如写原生 SQL）就静默失去隔离，而显式条件看得见。
 *
 * <p>{@code @Mapper} 注解其实可以省（启动类的 {@code @MapperScan} 已覆盖），
 * 这里保留是为了让「这个接口是 Mapper」在单个文件里自证，不依赖外部配置。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
