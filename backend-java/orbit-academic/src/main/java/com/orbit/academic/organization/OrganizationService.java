package com.orbit.academic.organization;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.user.User;
import com.orbit.academic.user.UserMapper;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 工作空间（组织）业务逻辑。
 *
 * <h2>⚠ 这里**故意不写** {@code scheduling_state}</h2>
 * Python 的 {@code POST /api/organizations} 会顺手插一行 {@code scheduling_state}：
 * <pre>{@code
 * db.add(SchedulingState(organization_id=organization.id, status="idle", message="尚未开始排课"))
 * }</pre>
 * 但 {@code scheduling_state} 是 **scheduling 服务的表**（见 README 包归属与
 * 方案 §3 铁律 2「禁止跨服务写库」）。academic 写它会在拆进程后立刻变成
 * 一个隐藏的跨库依赖 —— 而这正是 P1「先同进程验证边界」要提前暴露的问题。
 *
 * <p>那行为什么会在那里？因为 Python 的 {@code GET /api/schedule/status} 是这么写的：
 * <pre>{@code
 * state = db.query(SchedulingState).filter(...).first()
 * return _state_out(state)          # state 为 None 时 → AttributeError → 500
 * }</pre>
 * 它**要求**这行必须存在。所以在 Python 里「建组织」和「初始化排课状态」被绑在了一起。
 *
 * <h2>P1 采取的做法：把初始化搬到属主那边（懒初始化）</h2>
 * <ul>
 *   <li><b>academic</b>：只建组织 + 管理员用户，**不碰** {@code scheduling_state} ✓</li>
 *   <li><b>scheduling</b>：{@code /api/schedule/status} 查不到记录时，
 *       返回默认的 <code>{status:"idle", message:"尚未开始排课"}</code>，
 *       而不是空指针 500</li>
 * </ul>
 * 这样**对外可见的行为完全一致**（Python 返回那行 idle 状态，Java 返回等价的默认值），
 * 但跨服务写库消失了，且少了一个失败模式（组织建好了、排课状态没建）。
 *
 * <p>⚠ 过渡期注意：如果只启动 Java 版 academic 而 Python 版 scheduling 在处理请求，
 * 新建的组织在 Python 的 {@code /status} 上会 500。P1 是开发期的一次性切换，
 * 可以接受；正式迁移时两边一起切。
 */
@Service
public class OrganizationService {

    private static final Map<String, SFunction<Organization, ?>> SORTABLE = Map.of(
            "id", Organization::getId,
            "name", Organization::getName,
            "created_at", Organization::getCreatedAt);

    private static final String DEFAULT_SORT = "id";

    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public OrganizationService(OrganizationMapper organizationMapper,
                               UserMapper userMapper,
                               PasswordEncoder passwordEncoder) {
        this.organizationMapper = organizationMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 列出全部工作空间。
     *
     * <p>⚠ <b>这个接口天然不按租户过滤</b> —— 它是平台管理员用来管理所有学校的，
     * Python 侧也是 {@code db.query(Organization).order_by(Organization.id).all()}。
     * 权限由 Controller 上的平台管理员检查保证（403）。
     * 换句话说：**这是全项目唯一一个「跨租户读」的接口**，改动时务必留意。
     */
    public PageResponse<OrganizationResponse> list(PageQuery query) {
        LambdaQueryWrapper<Organization> wrapper = Wrappers.lambdaQuery();
        if (query.hasKeyword()) {
            wrapper.like(Organization::getName, query.keyword());
        }
        String column = query.orderColumn(SORTABLE.keySet(), DEFAULT_SORT);
        wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));

        Page<Organization> page = organizationMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        return PageResponse.of(
                page.getRecords().stream().map(OrganizationResponse::from).toList(),
                page.getTotal());
    }

    /**
     * 新建工作空间 + 其管理员账号（一个事务）。
     *
     * <p>错误码逐字对齐 Python：
     * <ul>
     *   <li>400 {@code 工作空间名称和管理员用户名不能为空}（空字符串，不是缺字段）</li>
     *   <li>409 {@code 工作空间名称已存在}</li>
     *   <li>409 {@code 管理员用户名已存在}</li>
     *   <li>400 口令太短 —— 编码器抛出的 {@code 密码至少需要 8 位}</li>
     * </ul>
     *
     * <p>用户名是**全局唯一**的（{@code users} 表上是 {@code UNIQUE KEY ix_users_username}，
     * 不是 (organization_id, username)），所以这里查重也不带组织条件。
     */
    @Transactional
    public OrganizationResponse create(OrganizationCreateRequest request) {
        String name = strip(request.name());
        String username = strip(request.adminUsername());
        if (name.isEmpty() || username.isEmpty()) {
            throw BusinessException.badRequest("工作空间名称和管理员用户名不能为空");
        }
        if (organizationMapper.selectCount(Wrappers.<Organization>lambdaQuery()
                .eq(Organization::getName, name)) > 0) {
            throw BusinessException.conflict("工作空间名称已存在");
        }
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)) > 0) {
            throw BusinessException.conflict("管理员用户名已存在");
        }

        String passwordHash;
        try {
            passwordHash = passwordEncoder.encode(request.adminPassword());
        } catch (IllegalArgumentException e) {
            // Python: except ValueError as exc → HTTPException(400, str(exc))
            throw BusinessException.badRequest(e.getMessage());
        }

        Organization organization = new Organization();
        organization.setName(name);
        // Python 侧由 Column(default=utcnow) 填 —— 必须显式写，且必须是 UTC（见 UtcTimes）
        organization.setCreatedAt(UtcTimes.now());
        organizationMapper.insert(organization);

        String displayName = strip(request.adminDisplayName());
        User admin = new User();
        admin.setOrganizationId(organization.getId());
        admin.setUsername(username);
        // Python: display_name=data.admin_display_name.strip() or username
        admin.setDisplayName(displayName.isEmpty() ? username : displayName);
        admin.setPasswordHash(passwordHash);
        admin.setRole("admin");
        admin.setIsActive(true);
        // 新管理员首次登录必须改密码（Python: must_change_password=True）
        admin.setMustChangePassword(true);
        admin.setCreatedAt(UtcTimes.now());
        userMapper.insert(admin);

        // ⚠ 刻意不插 scheduling_state，理由见类注释
        return OrganizationResponse.from(organization);
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
