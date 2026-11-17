import { api } from "./client";
export type KycStatus = "NOT_STARTED" | "PENDING" | "UNDER_REVIEW" | "VERIFIED" | "REJECTED";
export type KycTier = "BASIC" | "STANDARD" | "FULL";
export interface CustomerProfile { userId:string; fullName:string; email:string|null; phone:string|null; kycStatus:KycStatus; kycTier:KycTier; updatedAt:string; }
export interface KycDocument { id:string; documentType:string; fileName:string; contentType:string; fileSize:number; uploadedAt:string; }
export const customerApi = {
 profile: () => api.get<CustomerProfile>("/customers/me"),
 update: (body:{fullName:string;email:string;phone:string}) => api.put<CustomerProfile>("/customers/me",body),
 status: () => api.get<CustomerProfile>("/kyc/status"),
 documents: () => api.get<KycDocument[]>("/kyc/documents"),
 upload: (documentType:string,file:File) => { const form=new FormData(); form.append("documentType",documentType); form.append("file",file); return api.upload<KycDocument>("/kyc/documents",form); },
 submit: () => api.post<CustomerProfile>("/kyc/submit"),
};
