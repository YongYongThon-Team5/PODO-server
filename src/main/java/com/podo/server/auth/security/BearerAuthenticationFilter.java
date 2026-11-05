package com.podo.server.auth.security;

import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.auth.web.AuthCookies;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final AuthSessionService sessions;
    private final SecurityErrorWriter errors;

    public BearerAuthenticationFilter(AuthSessionService sessions, SecurityErrorWriter errors) {
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = AuthCookies.singleHeader(request, HttpHeaders.AUTHORIZATION, ErrorCode.INVALID_TOKEN);
            if (header != null) {
                if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    throw new BusinessException(ErrorCode.INVALID_TOKEN);
                }
                var identity = sessions.authenticateAccess(header.substring(7));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        identity, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                SecurityContextHolder.setContext(context);
            }
        } catch (BusinessException ex) {
            errors.write(response, ex.getErrorCode());
            return;
        } catch (DataAccessException ex) {
            errors.write(response, ErrorCode.SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }
}
