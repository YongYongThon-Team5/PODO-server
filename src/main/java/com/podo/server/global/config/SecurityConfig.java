package com.podo.server.global.config;

import com.podo.server.global.exception.ErrorCode;
import com.podo.server.auth.security.BearerAuthenticationFilter;
import com.podo.server.auth.security.SecurityErrorWriter;
import com.podo.server.auth.service.AuthSessionService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final SecurityErrorWriter errors;
    private final AuthSessionService sessions;

    public SecurityConfig(SecurityErrorWriter errors, AuthSessionService sessions) {
        this.errors = errors;
        this.sessions = sessions;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        ))

                // Refresh 쿠키를 사용하는 인증 POST는 AuthController에서 서명·Origin을 검증한다.
                // 나머지 개인 API는 브라우저가 자동 전송하지 않는 Bearer만으로 인증한다.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new BearerAuthenticationFilter(sessions, errors),
                        UsernamePasswordAuthenticationFilter.class)

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, ex) ->
                                errors.write(
                                        response,
                                        ErrorCode.AUTHENTICATION_REQUIRED
                                ))
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(
                                        response,
                                        ErrorCode.RESOURCE_NOT_FOUND
                                )))

                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/api/profile-options",
                                "/api/recommendation-options",
                                "/api/places",
                                "/api/places/**",
                                "/api/auth/csrf",
                                "/api/auth/login/kakao",
                                "/api/auth/callback/kakao"
                        )
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/recommendations",
                                "/api/auth/token"
                        )
                        .permitAll()

                        .requestMatchers("/api/**")
                        .authenticated()

                        .anyRequest()
                        .denyAll());

        return http.build();
    }
}
