package com.neobank.customer.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="kyc_documents") public class KycDocument {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; @Column(nullable=false) private UUID userId;
 @Column(nullable=false,length=40) private String documentType; @Column(nullable=false,length=255) private String fileName; @Column(nullable=false,length=100) private String contentType; @Column(nullable=false) private long fileSize; @Column(nullable=false) private byte[] content; @Column(nullable=false) private Instant uploadedAt;
 protected KycDocument(){} public KycDocument(UUID uid,String type,String name,String mime,byte[] bytes){userId=uid;documentType=type;fileName=name;contentType=mime;content=bytes;fileSize=bytes.length;uploadedAt=Instant.now();}
 public UUID getId(){return id;} public UUID getUserId(){return userId;} public String getDocumentType(){return documentType;} public String getFileName(){return fileName;} public String getContentType(){return contentType;} public long getFileSize(){return fileSize;} public byte[] getContent(){return content;} public Instant getUploadedAt(){return uploadedAt;}
}