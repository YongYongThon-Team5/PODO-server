package com.podo.server.auth.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("podo.auth.kakao")
public record KakaoProperties(String clientId, String clientSecret, URI redirectUri) {
}
