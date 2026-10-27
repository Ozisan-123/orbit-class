package com.orbit.academic.config;

import com.orbit.common.util.UtcTimes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 空库首次启动的安全引导，与 Python 版 bootstrap_security 行为对齐。
 * 仅当库里没有用户时创建平台管理员；已有数据绝不会覆盖密码或账号。
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public BootstrapAdminInitializer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                     @Value("${ORBIT_ADMIN_USERNAME:admin}") String username,
                                     @Value("${ORBIT_ADMIN_PASSWORD:Admin@123}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long organizationId = firstOrganizationId();
        if (organizationId == null) organizationId = createDefaultOrganization();

        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (userCount == null || userCount == 0) {
            jdbc.update("""
                    INSERT INTO users
                    (organization_id,username,display_name,password_hash,role,is_active,must_change_password,created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, organizationId, username, "系统管理员", passwordEncoder.encode(password),
                    "platform_admin", true, true, UtcTimes.now());
            return;
        }

        Integer platformAdmins = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='platform_admin'", Integer.class);
        if (platformAdmins == null || platformAdmins == 0) {
            List<Long> admins = jdbc.query("""
                    SELECT id FROM users WHERE organization_id=? AND role='admin' ORDER BY id LIMIT 1
                    """, (rs, row) -> rs.getLong(1), organizationId);
            if (!admins.isEmpty()) {
                jdbc.update("UPDATE users SET role='platform_admin' WHERE id=?", admins.getFirst());
            }
        }
    }

    private Long firstOrganizationId() {
        List<Long> ids = jdbc.query("SELECT id FROM organizations ORDER BY id LIMIT 1",
                (rs, row) -> rs.getLong(1));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long createDefaultOrganization() {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO organizations (name,created_at) VALUES (?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "默认学校");
            statement.setTimestamp(2, Timestamp.valueOf(UtcTimes.now()));
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
