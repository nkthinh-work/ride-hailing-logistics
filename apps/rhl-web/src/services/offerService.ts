import type {
  DriverOfferResponse,
  RejectOfferRequest,
} from '../types/index.ts';
import { request } from './apiClient.ts';

export const offerService = {
  /**
   * Driver accepts an offer (FR-MAT-009, FR-MAT-010).
   * Calls POST /api/v1/offers/{offerId}/accept
   */
  acceptOffer: async (offerId: string): Promise<DriverOfferResponse> => {
    return request<DriverOfferResponse>(`/api/v1/offers/${offerId}/accept`, {
      method: 'POST',
    });
  },

  /**
   * Driver rejects an offer (FR-MAT-009).
   * Calls POST /api/v1/offers/{offerId}/reject
   */
  rejectOffer: async (offerId: string, reason?: string): Promise<DriverOfferResponse> => {
    const body: RejectOfferRequest | undefined = reason ? { reason } : undefined;
    return request<DriverOfferResponse>(`/api/v1/offers/${offerId}/reject`, {
      method: 'POST',
      body: body ? JSON.stringify(body) : undefined,
    });
  },

  /**
   * Retrieves active pending offer for the authenticated driver.
   * Calls GET /api/v1/offers/pending
   */
  getPendingOffer: async (): Promise<DriverOfferResponse | null> => {
    try {
      return await request<DriverOfferResponse>('/api/v1/offers/pending');
    } catch (err: unknown) {
      // 404 or empty response means no pending offer
      if (err && typeof err === 'object' && 'status' in err && (err as { status: number }).status === 404) {
        return null;
      }
      throw err;
    }
  },

  /**
   * Retrieves offer details by ID.
   * Calls GET /api/v1/offers/{offerId}
   */
  getOfferById: async (offerId: string): Promise<DriverOfferResponse> => {
    return request<DriverOfferResponse>(`/api/v1/offers/${offerId}`);
  },
};
