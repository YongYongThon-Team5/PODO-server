package com.podo.server.auth.withdrawal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("podo.auth.withdrawal")
public record WithdrawalProperties(
        @DefaultValue("") String adminKey,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration requestTimeout,
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("5s") Duration pollInterval
) {

    public boolean ready() {
        return adminKey != null && adminKey.matches("[A-Za-z0-9_-]+")
                && validHttpTimeout(connectTimeout) && validHttpTimeout(requestTimeout)
                // WithdrawalRepository currently claims a fixed five-minute lease.
                && Duration.ofMinutes(5).equals(leaseDuration)
                && leaseDuration.minus(connectTimeout).compareTo(requestTimeout) > 0
                && pollInterval != null && pollInterval.compareTo(Duration.ofMillis(1)) >= 0;
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
