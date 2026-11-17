package com.neobank.auth.repository;

import com.neobank.auth.domain.MfaCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MfaCredentialRepository extends JpaRepository<MfaCredential, UUID> {
}
