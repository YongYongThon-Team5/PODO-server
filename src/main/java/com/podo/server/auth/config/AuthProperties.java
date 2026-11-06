package com.podo.server.auth.config;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("podo.auth")
public record AuthProperties(String apiOrigin, URI successRedirectUri, URI failureRedirectUri) {

    public String requiredOrigin() {
        try {
            URI origin = URI.create(apiOrigin);
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())) {
                throw new IllegalArgumentException();
            }
            return apiOrigin;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public URI redirect(boolean success) {
        URI origin = URI.create(requiredOrigin());
        URI target = success ? successRedirectUri : failureRedirectUri;
        if (target == null || target.getRawUserInfo() != null
                || !origin.getScheme().equals(target.getScheme())
                || !origin.getHost().equalsIgnoreCase(target.getHost())
                || port(origin) != port(target)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return target;
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }
}
