package com.podo.server.auth.service;

import java.util.UUID;

public record AuthenticatedAccount(UUID accountId, UUID sessionId) {
}
