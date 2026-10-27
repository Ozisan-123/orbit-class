package com.orbit.assistant.security;

import com.orbit.common.security.AuthFailure;
import com.orbit.common.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class AssistantJwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public AssistantJwtFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader("Authorization");
            String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? header.substring(7).trim() : null;
            if (token == null || token.isBlank()) {
                request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.NO_TOKEN);
            } else {
                jwtService.userIdOf(token).ifPresentOrElse(
                        id -> authenticate(request, id),
                        () -> request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.INVALID_TOKEN));
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, long userId) {
        List<AssistantPrincipal> users = jdbc.query("""
                SELECT id, organization_id, username, display_name, role
                FROM users WHERE id = ? AND is_active = 1
                """, (rs, row) -> new AssistantPrincipal(
                rs.getLong("id"), rs.getLong("organization_id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("role")), userId);
        if (users.isEmpty()) {
            request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.UNKNOWN_USER);
            return;
        }
        AssistantPrincipal principal = users.getFirst();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(
                "ROLE_" + principal.role().toUpperCase(Locale.ROOT))));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
