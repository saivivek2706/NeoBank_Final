package com.neobank.auth.config;

import com.neobank.auth.domain.Role;
import com.neobank.auth.domain.User;
import com.neobank.auth.repository.UserRepository;
import com.neobank.auth.security.BlindIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserSeederTest {

    @Mock private UserRepository userRepository;
    @Mock private BlindIndexService blindIndexService;
    @Mock private PasswordEncoder passwordEncoder;

    private AdminUserSeeder seeder(String email, String password) {
        return new AdminUserSeeder(userRepository, blindIndexService, passwordEncoder, email, password);
    }

    @Test
    void seedsNothingWhenNoCredentialsAreConfigured_thereIsNoBuiltInDefault() {
        seeder("", "").run(null);
        seeder(null, null).run(null);
        seeder("admin@example.com", "").run(null);
        seeder("", "a-long-enough-password").run(null);

        verifyNoInteractions(userRepository);
    }

    @Test
    void createsAdminWithHashedPasswordWhenConfigured() {
        when(blindIndexService.hashEmail("admin@example.com")).thenReturn("hash");
        when(userRepository.existsByEmailHash("hash")).thenReturn(false);
        when(passwordEncoder.encode("a-long-enough-password")).thenReturn("$2a$encoded");

        seeder("admin@example.com", "a-long-enough-password").run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$encoded");
        assertThat(captor.getValue().getPasswordHash()).doesNotContain("a-long-enough-password");
    }

    @Test
    void isIdempotent_existingAdminIsLeftUntouched() {
        when(blindIndexService.hashEmail("admin@example.com")).thenReturn("hash");
        when(userRepository.existsByEmailHash("hash")).thenReturn(true);

        seeder("admin@example.com", "a-long-enough-password").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesAWeakBootstrapPassword() {
        assertThatThrownBy(() -> seeder("admin@example.com", "short").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("12");

        verifyNoInteractions(userRepository);
    }
}
