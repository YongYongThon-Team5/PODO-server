package com.podo.server.global.config;

import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

                // 추천은 공개 조회 요청이므로 기본 CSRF 검사에서 제외한다.
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/recommendations"))

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(
                                        response,
                                        ErrorCode.AUTHENTICATION_REQUIRED
                                ))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(
                                        response,
                                        ex instanceof CsrfException
                                                ? ErrorCode.CSRF_INVALID
                                                : ErrorCode.RESOURCE_NOT_FOUND
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
                                "/api/recommendations"
                        )
                        .permitAll()

                        .requestMatchers("/api/**")
                        .authenticated()

                        .anyRequest()
                        .denyAll());

        // 카카오 로그인·자체 Bearer 토큰 검증·서명된 CSRF는
        // 인증 기능을 구현할 때 연결한다.
        return http.build();
    }

    private void writeError(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {

        ErrorResponse body = ErrorResponse.of(errorCode);

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Request-Id", body.error().requestId());

        if (errorCode.getStatus().value() == 401) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}