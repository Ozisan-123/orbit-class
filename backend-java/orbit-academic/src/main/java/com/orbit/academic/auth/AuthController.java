package com.orbit.academic.auth;

import com.orbit.academic.organization.Organization;
import com.orbit.academic.organization.OrganizationMapper;
import com.orbit.academic.user.User;
import com.orbit.academic.user.UserMapper;
import com.orbit.academic.user.UserResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口 —— {@code /api/auth/*}（3 个），对应 Python 的 {@code routers/auth.py}。
 *
 * <p>内部走 Spring Security 的标准流程，但**对外行为逐字对齐 Python**，
 * 包括每一条错误文案与状态码（见下表的实测值）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final UserMapper userMapper;
    private final OrganizationMapper organizationMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          LoginAttemptGuard loginAttemptGuard,
                          UserMapper userMapper,
                          OrganizationMapper organizationMapper,
                          PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.userMapper = userMapper;
        this.organizationMapper = organizationMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 登录。
     *
     * <p>与 Python {@code routers/auth.py:13-28} 的对应关系：
     * <table>
     *   <tr><th>情况</th><th>Python</th><th>这里</th></tr>
     *   <tr><td>失败次数超限</td><td>429 {@code 登录失败次数过多，请在 N 秒后重试} + {@code Retry-After}</td><td>同</td></tr>
     *   <tr><td>用户不存在 / 停用 / 口令错</td><td>401 {@code 用户名或密码错误}（同一个分支，不区分）</td><td>同（捕获所有 {@code AuthenticationException}）</td></tr>
     *   <tr><td>成功</td><td>200 {@code {access_token, token_type:"bearer", user}}</td><td>同</td></tr>
     * </table>
     *
     * <p><b>为什么把所有 {@code AuthenticationException} 合并成一条消息</b>：
     * Spring 会因为「用户不存在」（{@code BadCredentialsException}）与
     * 「账号停用」（{@code DisabledException}）抛出不同异常。
     * 分开报告等于告诉攻击者「这个用户名是存在的」—— Python 那边刻意合并成一个分支，
     * 这里保持一致。
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        String username = request.username() == null ? "" : request.username();
        String password = request.password() == null ? "" : request.password();
        String clientIp = clientIp(httpRequest);

        int retryAfter = loginAttemptGuard.retryAfterSeconds(username, clientIp);
        if (retryAfter > 0) {
            throw BusinessException.tooManyRequests(
                    "登录失败次数过多，请在 " + retryAfter + " 秒后重试", retryAfter);
        }

        OrbitUserPrincipal principal;
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username.strip(), password));
            principal = (OrbitUserPrincipal) authentication.getPrincipal();
        } catch (AuthenticationException e) {
            // 只记 debug：登录失败是正常业务事件，不是系统故障。
            // 但也别记口令 —— 连长度都不要记。
            log.debug("登录失败 user={} ip={} reason={}", username, clientIp, e.getClass().getSimpleName());
            loginAttemptGuard.recordFailure(username, clientIp);
            throw BusinessException.unauthorized("用户名或密码错误");
        }

        loginAttemptGuard.clear(username, clientIp);
        return LoginResponse.of(jwtService.issue(principal.userId()), toResponse(principal));
    }

    /** 当前用户。对应 Python {@code GET /api/auth/me}。 */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal OrbitUserPrincipal principal) {
        return toResponse(requirePrincipal(principal));
    }

    /**
     * 修改密码。对应 Python {@code POST /api/auth/change-password}。
     *
     * <p>注意「新密码至少 8 位」是由编码器抛异常、这里转成 <b>400</b> 的
     * （对应 Python 的 {@code except ValueError → 400}）——
     * 如果在 DTO 上用 {@code @Size(min=8)} 会变成 422，状态码就不一致了。
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                              @AuthenticationPrincipal OrbitUserPrincipal principal) {
        OrbitUserPrincipal current = requirePrincipal(principal);
        User user = userMapper.selectById(current.userId());
        if (user == null || !user.active()) {
            throw BusinessException.unauthorized("账号不存在或已停用");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw BusinessException.badRequest("当前密码不正确");
        }

        String encoded;
        try {
            encoded = passwordEncoder.encode(request.newPassword());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest(e.getMessage());
        }

        // 只更新这两列（MyBatis-Plus 默认忽略 null 字段）
        User patch = new User();
        patch.setId(user.getId());
        patch.setPasswordHash(encoded);
        patch.setMustChangePassword(false);
        userMapper.updateById(patch);

        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ 工具

    private UserResponse toResponse(OrbitUserPrincipal principal) {
        Organization organization = organizationMapper.selectById(principal.organizationId());
        return principal.toResponse(organization == null ? null : organization.getName());
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            // 正常情况下到不了这里（授权层已保证 authenticated），
            // 但 @AuthenticationPrincipal 在异常场景下确实可能是 null，
            // 那时应该给 401 而不是 NullPointerException → 500
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }

    /**
     * 客户端 IP，用于登录限流。
     *
     * <p>Python 用的是 {@code request.client.host}（直连地址）。这里保持一致用
     * {@code getRemoteAddr()}。
     * TODO(P3)：上网关之后所有请求的 remoteAddr 都会变成网关地址，
     * 那时要改成读 {@code X-Forwarded-For}（并且只信网关写入的那一段）。
     */
    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
