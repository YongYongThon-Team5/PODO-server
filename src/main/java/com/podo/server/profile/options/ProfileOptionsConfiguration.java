package com.podo.server.profile.options;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileOptionsProperties.class)
public class ProfileOptionsConfiguration {
}
