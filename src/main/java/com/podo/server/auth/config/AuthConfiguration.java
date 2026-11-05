package com.podo.server.auth.config;

import com.podo.server.auth.kakao.KakaoProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, KakaoProperties.class})
public class AuthConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
