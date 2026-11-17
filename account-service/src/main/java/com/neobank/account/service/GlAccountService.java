package com.neobank.account.service;

import com.neobank.account.domain.GlAccount;
import com.neobank.account.domain.SystemAccount;
import com.neobank.account.repository.GlAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Looks up (creating on first use) the bank's system ledger accounts for a currency. */
@Service
@RequiredArgsConstructor
public class GlAccountService {

    private final GlAccountRepository glAccountRepository;

    @Transactional
    public GlAccount system(SystemAccount account, String currency) {
        return glAccountRepository.findByCodeAndCurrency(account.name(), currency).orElseGet(() -> {
            glAccountRepository.insertIfAbsent(UUID.randomUUID(), account.name(), account.displayName(),
                    account.type().name(), currency);
            return glAccountRepository.findByCodeAndCurrency(account.name(), currency)
                    .orElseThrow(() -> new IllegalStateException("Could not create GL account " + account + " " + currency));
        });
    }
}
