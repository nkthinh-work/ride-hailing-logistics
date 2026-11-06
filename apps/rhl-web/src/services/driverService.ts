import type {
  DriverApplicationRequest,
  DriverApplicationResponse,
  DriverEligibilityResponse,
  DriverStatus,
  RejectDriverRequest,
} from '../types';
import { request } from './apiClient';

export const driverService = {
  apply: async (data: DriverApplicationRequest): Promise<DriverApplicationResponse> => {
    return request<DriverApplicationResponse>('/api/v1/drivers/applications', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  getMyApplication: async (): Promise<DriverApplicationResponse> => {
    return request<DriverApplicationResponse>('/api/v1/drivers/applications/me');
  },

  getMyEligibility: async (): Promise<DriverEligibilityResponse> => {
    return request<DriverEligibilityResponse>('/api/v1/drivers/applications/me/eligibility');
  },

  listApplications: async (status: DriverStatus = 'PENDING'): Promise<DriverApplicationResponse[]> => {
    return request<DriverApplicationResponse[]>(`/api/v1/drivers/applications?status=${status}`);
  },

  approve: async (applicationId: string): Promise<DriverApplicationResponse> => {
    return request<DriverApplicationResponse>(`/api/v1/drivers/applications/${applicationId}/approve`, {
      method: 'PATCH',
    });
  },

  reject: async (applicationId: string, reason: string): Promise<DriverApplicationResponse> => {
    const payload: RejectDriverRequest = { reason };
    return request<DriverApplicationResponse>(`/api/v1/drivers/applications/${applicationId}/reject`, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    });
  },
};
