import type { FareQuoteRequest, FareQuoteResponse } from '../types/index.ts';
import { request } from './apiClient.ts';

export const fareService = {
  /**
   * Request an immutable Fare Quote from Pricing Service (FR-PRI-013..015).
   * Calls POST /api/v1/fares/quote
   */
  createQuote: async (body: FareQuoteRequest): Promise<FareQuoteResponse> => {
    return request<FareQuoteResponse>('/api/v1/fares/quote', {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  /**
   * Retrieves an issued Fare Quote by quoteId.
   * Calls GET /api/v1/fares/quotes/{quoteId}
   */
  getQuoteById: async (quoteId: string): Promise<FareQuoteResponse> => {
    return request<FareQuoteResponse>(`/api/v1/fares/quotes/${quoteId}`);
  },
};
