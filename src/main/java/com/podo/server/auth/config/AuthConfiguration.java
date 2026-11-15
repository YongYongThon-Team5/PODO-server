package com.podo.server.auth.config;

import com.podo.server.auth.kakao.KakaoProperties;
import com.podo.server.auth.withdrawal.WithdrawalProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, KakaoProperties.class, WithdrawalProperties.class})
@EnableScheduling
public class AuthConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
