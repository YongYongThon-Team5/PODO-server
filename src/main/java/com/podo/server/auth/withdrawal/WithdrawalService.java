package com.podo.server.auth.withdrawal;

import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalService {
    private final AccountRepository accounts;
    private final AuthSessionService sessions;
    private final WithdrawalRepository jobs;
    private final KakaoUnlinkClient unlink;
    private final Clock clock;

    @Transactional
    public void request(UUID accountId) {
        if (!unlink.ready()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        var account = accounts.findLockedById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        if (!account.isActive()) throw new BusinessException(ErrorCode.INVALID_TOKEN);
        account.beginWithdrawal();
        sessions.revokeAllForAccount(accountId);
        jobs.enqueue(accountId, clock.instant());
    }
}
