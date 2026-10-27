package com.orbit.academic.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.academic.user.User;
import com.orbit.academic.user.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 按用户名/ID 装载认证主体 —— Spring Security 的标准入口。
 *
 * <p>{@link UserDetailsService} 是给 {@code DaoAuthenticationProvider} 用的（登录时）；
 * {@link #loadById(long)} 是给 JWT 过滤器用的（每次请求凭 token 里的 uid 装载）。
 * 两者共用同一段查库逻辑，避免"登录时查一套、校验 token 时查另一套"而逐渐漂移。
 *
 * <p><b>为什么按 ID 也查一次库</b>：token 里只放了 uid。如果直接把 token 里的 uid
 * 当作已认证用户，那么「用户被停用/删除后 token 还能用」—— Python 侧同样每请求查库
 * （{@code get_current_user} 里带 {@code is_active.is_(True)}），这里保持一致。
 */
@Service
public class OrbitUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public OrbitUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null) {
            throw new UsernameNotFoundException("用户名不能为空");
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim()));
        if (user == null) {
            // 消息不会外泄（登录接口把一切失败统一成「用户名或密码错误」），
            // 但日志里要能看出是"查无此人"还是"口令错"。
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return OrbitUserPrincipal.from(user);
    }

    /**
     * 凭 token 里的 uid 装载。
     *
     * <p>停用账号返回 {@link UsernameNotFoundException}（过滤器会当作未认证 → 401），
     * 对应 Python 的 {@code filter(User.is_active.is_(True))}
     * 与 {@code detail="账号不存在或已停用"}。
     */
    public OrbitUserPrincipal loadById(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !user.active()) {
            throw new UsernameNotFoundException("账号不存在或已停用: " + userId);
        }
        return OrbitUserPrincipal.from(user);
    }
}
