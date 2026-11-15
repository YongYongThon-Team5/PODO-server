package com.podo.server.auth.withdrawal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationStyle;

@ConfigurationProperties("podo.auth.withdrawal")
public final class WithdrawalProperties {

    private final String adminKey;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration leaseDuration;
    private final Duration pollInterval;

    @ConstructorBinding
    public WithdrawalProperties(
            @DefaultValue("") String adminKey,
            @DefaultValue("5s") String connectTimeout,
            @DefaultValue("15s") String requestTimeout,
            @DefaultValue("5m") String leaseDuration,
            @DefaultValue("5s") String pollInterval) {
        this(adminKey, parseDuration(connectTimeout), parseDuration(requestTimeout),
                parseDuration(leaseDuration), parseDuration(pollInterval));
    }

    public WithdrawalProperties(String adminKey, Duration connectTimeout, Duration requestTimeout,
            Duration leaseDuration, Duration pollInterval) {
        this.adminKey = adminKey;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.leaseDuration = leaseDuration;
        this.pollInterval = pollInterval;
    }

    public String adminKey() {
        return adminKey;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public boolean ready() {
        return adminKey != null && adminKey.matches("[A-Za-z0-9_-]+")
                && validHttpTimeout(connectTimeout) && validHttpTimeout(requestTimeout)
                // WithdrawalRepository currently claims a fixed five-minute lease.
                && Duration.ofMinutes(5).equals(leaseDuration)
                && leaseDuration.minus(connectTimeout).compareTo(requestTimeout) > 0
                && pollInterval != null && pollInterval.compareTo(Duration.ofMillis(1)) >= 0;
    }

    private static Duration parseDuration(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(value.trim());
        } catch (IllegalArgumentException | ArithmeticException exception) {
            // Invalid withdrawal configuration disables withdrawal, not the existing login app.
            return null;
        }
    }

    private static boolean validHttpTimeout(Duration timeout) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0;
    }

    @Override
    public String toString() {
        return "WithdrawalProperties[adminKey=<redacted>]";
    }
}
