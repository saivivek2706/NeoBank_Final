package com.neobank.customer.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="customer_profiles") public class CustomerProfile {
 @Id private UUID userId;
 @Column(nullable=false,length=120) private String fullName;
 @Column(length=254) private String email;
 @Column(length=30) private String phone;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private KycStatus kycStatus=KycStatus.NOT_STARTED;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private KycTier kycTier=KycTier.BASIC;
 @Column(nullable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
 protected CustomerProfile(){}
 public CustomerProfile(UUID id,String name,String email,String phone){this.userId=id;this.fullName=name;this.email=email;this.phone=phone;this.createdAt=Instant.now();this.updatedAt=createdAt;}
 public UUID getUserId(){return userId;} public String getFullName(){return fullName;} public String getEmail(){return email;} public String getPhone(){return phone;} public KycStatus getKycStatus(){return kycStatus;} public KycTier getKycTier(){return kycTier;} public Instant getUpdatedAt(){return updatedAt;}
 public void update(String name,String email,String phone){this.fullName=name;this.email=email;this.phone=phone;this.updatedAt=Instant.now();}
 public void submit(){if(kycStatus==KycStatus.VERIFIED)throw new IllegalStateException("KYC already verified");kycStatus=KycStatus.PENDING;updatedAt=Instant.now();}
 public void review(KycStatus status,KycTier tier){if(status!=KycStatus.VERIFIED&&status!=KycStatus.REJECTED)throw new IllegalArgumentException("Review must be VERIFIED or REJECTED");kycStatus=status;kycTier=status==KycStatus.VERIFIED?tier:KycTier.BASIC;updatedAt=Instant.now();}
}