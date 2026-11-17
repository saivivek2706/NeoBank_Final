import { api } from "./client";
export interface Beneficiary {id:string;destinationAccountId:string;nickname:string;createdAt:string;availableAt:string;active:boolean;}
export const beneficiaryApi={list:()=>api.get<Beneficiary[]>("/beneficiaries"),add:(body:{destinationAccountId:string;nickname:string})=>api.post<Beneficiary>("/beneficiaries",body),remove:(id:string)=>api.delete<void>(`/beneficiaries/${id}`)};
