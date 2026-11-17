package com.neobank.auth.repository;

import com.neobank.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    // email is encrypted at rest with a random IV per row, so it can't be
    // queried by equality - lookups go through the deterministic blind index
    // instead (see BlindIndexService, User.emailHash).
    Optional<User> findByEmailHash(String emailHash);
    boolean existsByEmailHash(String emailHash);
}
