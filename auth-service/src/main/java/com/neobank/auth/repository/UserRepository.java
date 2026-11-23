package com.neobank.auth.repository;

import com.neobank.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    // email is encrypted at rest with a random IV per row, so it can't be
    // queried by equality - lookups go through the deterministic blind index
    // instead (see BlindIndexService, User.emailHash).
    Optional<User> findByEmailHash(String emailHash);
    boolean existsByEmailHash(String emailHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
