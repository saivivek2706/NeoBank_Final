package com.neobank.customer.repository;
import com.neobank.customer.domain.CustomerProfile; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile,UUID>{}