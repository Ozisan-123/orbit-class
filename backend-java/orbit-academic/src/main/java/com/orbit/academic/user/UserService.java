package com.orbit.academic.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.organization.Organization;
import com.orbit.academic.organization.OrganizationMapper;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 账号管理。对应 Python {@code routers/users.py}（3 个接口：list / create / update）。
 *
 * <p>注意**没有删除接口** —— Python 侧也没实现（账号停用走 {@code is_active=false}）。
 * 不要"顺手补一个"，那会变成契约之外的新能力。
 */
@Service
public class UserService {

    /** Python 的 {@code VALID_ROLES}：建/改账号时只允许这两个角色。 */
    public static final Set<String> VALID_ROLES = Set.of("admin", "operator");

    private final UserMapper userMapper;
    private final OrganizationMapper organizationMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper,
                       OrganizationMapper organizationMapper,
                       PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.organizationMapper = organizationMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /** 本组织的账号列表（Python 按 id 排序）。 */
    public PageResponse<UserResponse> list(long organizationId, PageQuery query) {
        var wrapper = Wrappers.<User>lambdaQuery()
                .eq(User::getOrganizationId, organizationId);
        if (query.hasKeyword()) {
            wrapper.and(w -> w.like(User::getUsername, query.keyword())
                    .or().like(User::getDisplayName, query.keyword()));
        }
        wrapper.orderBy(true, query.ascending(), User::getId);

        Page<User> page = userMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        String organizationName = organizationName(organizationId);
        return PageResponse.of(
                page.getRecords().stream()
                        .map(u -> UserResponse.of(u, organizationName))
                        .toList(),
                page.getTotal());
    }

    /**
     * 新建账号。
     *
     * <p>错误码与文案逐字对齐 Python：
     * <ul>
     *   <li>400 {@code 用户名不能为空}（空字符串，不是缺字段）</li>
     *   <li>400 {@code 无效的账号角色}</li>
     *   <li>409 {@code 用户名已存在}（用户名**全局唯一**，不按组织隔离）</li>
     *   <li>400 {@code 密码至少需要 8 位}</li>
     * </ul>
     * 新建的账号 {@code must_change_password=true}（首次登录强制改密），
     * 与组织创建时的管理员一样。
     */
    @Transactional
    public UserResponse create(long organizationId, UserCreateRequest request) {
        String username = strip(request.username());
        if (username.isEmpty()) {
            throw BusinessException.badRequest("用户名不能为空");
        }
        String role = request.role() == null || request.role().isBlank() ? "operator" : request.role();
        if (!VALID_ROLES.contains(role)) {
            throw BusinessException.badRequest("无效的账号角色");
        }
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)) > 0) {
            throw BusinessException.conflict("用户名已存在");
        }
        String passwordHash;
        try {
            passwordHash = passwordEncoder.encode(request.password());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest(e.getMessage());
        }

        String displayName = strip(request.displayName());
        User user = new User();
        user.setOrganizationId(organizationId);
        user.setUsername(username);
        // Python: display_name=data.display_name.strip() or username
        user.setDisplayName(displayName.isEmpty() ? username : displayName);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setIsActive(true);
        user.setMustChangePassword(true);
        user.setCreatedAt(UtcTimes.now());
        userMapper.insert(user);

        return UserResponse.of(user, organizationName(organizationId));
    }

    /**
     * 修改账号。
     *
     * <p>三条**自我保护**规则（Python 原文，文案逐字保留）：
     * <ul>
     *   <li>不能把自己的角色改成非管理员 → 400 {@code 不能取消自己的管理员权限}</li>
     *   <li>不能停用自己 → 400 {@code 不能停用自己的账号}</li>
     * </ul>
     * 没有这两条，一个管理员一次误操作就能把自己锁在系统外面。
     */
    @Transactional
    public UserResponse update(long organizationId, long userId, long operatorUserId,
                               UserUpdateRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getId, userId)
                .eq(User::getOrganizationId, organizationId));
        if (user == null) {
            throw BusinessException.notFound("账号不存在");
        }

        if (request.role() != null) {
            if (!VALID_ROLES.contains(request.role())) {
                throw BusinessException.badRequest("无效的账号角色");
            }
            if (user.getId() == operatorUserId
                    && !"admin".equals(request.role()) && !"platform_admin".equals(request.role())) {
                throw BusinessException.badRequest("不能取消自己的管理员权限");
            }
            user.setRole(request.role());
        }
        if (request.isActive() != null) {
            if (user.getId() == operatorUserId && !request.isActive()) {
                throw BusinessException.badRequest("不能停用自己的账号");
            }
            user.setIsActive(request.isActive());
        }
        if (request.displayName() != null) {
            String displayName = strip(request.displayName());
            user.setDisplayName(displayName.isEmpty() ? user.getUsername() : displayName);
        }
        if (request.password() != null && !request.password().isEmpty()) {
            try {
                user.setPasswordHash(passwordEncoder.encode(request.password()));
            } catch (IllegalArgumentException e) {
                throw BusinessException.badRequest(e.getMessage());
            }
            user.setMustChangePassword(true);
        }
        userMapper.updateById(user);

        return UserResponse.of(user, organizationName(organizationId));
    }

    private String organizationName(long organizationId) {
        Organization organization = organizationMapper.selectById(organizationId);
        return organization == null ? null : organization.getName();
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** 供其它领域复用的只读查询（列表页需要用户显示名时用）。 */
    public List<User> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(ids);
    }
}
