package com.neobank.account.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.open("NB100000001", UUID.randomUUID(), "Alice Tan",
                AccountType.SAVINGS, "SGD", new BigDecimal("1000.00"));
    }

    @Test
    void getMaskedAccountNumber_showsOnlyLastFourDigits() {
        assertThat(account.getMaskedAccountNumber()).isEqualTo("••••0001");
    }

    @Test
    void getMaskedAccountNumber_doesNotLeakAnyOtherDigits() {
        assertThat(account.getMaskedAccountNumber()).doesNotContain("NB10000");
    }

    @Test
    void debit_reducesBalance_whenSufficientFunds() {
        account.debit(new BigDecimal("250.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("750.00");
    }

    @Test
    void debit_throws_whenInsufficientFunds() {
        assertThatThrownBy(() -> account.debit(new BigDecimal("5000.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient balance");

        // Critically: the balance must be untouched after a failed debit -
        // no partial mutation before the check fails.
        assertThat(account.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void debit_throws_whenAccountFrozen() {
        account.freeze();

        assertThatThrownBy(() -> account.debit(new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    void credit_increasesBalance() {
        account.credit(new BigDecimal("500.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("1500.00");
    }

    @Test
    void credit_throws_whenAccountFrozen() {
        account.freeze();

        assertThatThrownBy(() -> account.credit(new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reverseDebit_restoresFunds_evenOnFrozenAccount() {
        // Compensation must succeed regardless of account status - if a
        // downstream credit fails on a frozen receiver, the sender's reversal
        // cannot be blocked by an unrelated status check, or the saga can
        // never resolve.
        account.debit(new BigDecimal("300.00"));
        account.freeze();

        account.reverseDebit(new BigDecimal("300.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void unfreeze_allowsFurtherTransactions() {
        account.freeze();
        account.unfreeze();

        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("900.00");
    }
}
