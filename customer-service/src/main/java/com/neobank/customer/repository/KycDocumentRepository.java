package com.neobank.customer.repository;
import com.neobank.customer.domain.KycDocument; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface KycDocumentRepository extends JpaRepository<KycDocument,UUID>{List<KycDocument> findByUserIdOrderByUploadedAtDesc(UUID userId); Optional<KycDocument> findByIdAndUserId(UUID id,UUID userId);}