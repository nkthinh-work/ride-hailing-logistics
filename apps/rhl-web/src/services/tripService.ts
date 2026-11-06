import type {
  TripResponse,
  TripLocationSnapshotResponse,
  CreateTripRequest,
} from '../types/index.ts';
import { request } from './apiClient.ts';

export interface CancelTripBody {
  reason: string;
}

export const tripService = {
  /**
   * Creates a new trip booking based on customer's quote (FR-TRIP-001..002).
   * Calls POST /api/v1/trips
   */
  bookTrip: async (body: CreateTripRequest): Promise<TripResponse> => {
    return request<TripResponse>('/api/v1/trips', {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  /**
   * Retrieves trip details by ID (customer or admin).
   * Calls GET /api/v1/trips/{tripId}
   */
  getTripById: async (tripId: string): Promise<TripResponse> => {
    return request<TripResponse>(`/api/v1/trips/${tripId}`);
  },

  /**
   * Retrieves authoritative driver location snapshot for the trip.
   * Calls GET /api/v1/trips/{tripId}/location
   */
  getTripLocationSnapshot: async (tripId: string): Promise<TripLocationSnapshotResponse> => {
    return request<TripLocationSnapshotResponse>(`/api/v1/trips/${tripId}/location`);
  },

  /**
   * Cancels an active trip (FR-CAN-001..007).
   * Calls POST /api/v1/trips/{tripId}/cancel
   */
  cancelTrip: async (tripId: string, reason: string): Promise<TripResponse> => {
    const body: CancelTripBody = { reason };
    return request<TripResponse>(`/api/v1/trips/${tripId}/cancel`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  /**
   * Driver starts heading to pickup location (ACCEPTED -> PICKING_UP).
   * Calls POST /api/v1/trips/{tripId}/start-pickup
   */
  startPickingUp: async (tripId: string): Promise<TripResponse> => {
    return request<TripResponse>(`/api/v1/trips/${tripId}/start-pickup`, {
      method: 'POST',
    });
  },

  /**
   * Driver confirms arrival at pickup location (PICKING_UP -> ARRIVED).
   * Calls POST /api/v1/trips/{tripId}/arrive
   */
  confirmArrived: async (tripId: string): Promise<TripResponse> => {
    return request<TripResponse>(`/api/v1/trips/${tripId}/arrive`, {
      method: 'POST',
    });
  },

  /**
   * Passenger on board; driver starts the journey (ARRIVED -> IN_TRIP).
   * Calls POST /api/v1/trips/{tripId}/start
   */
  startTrip: async (tripId: string): Promise<TripResponse> => {
    return request<TripResponse>(`/api/v1/trips/${tripId}/start`, {
      method: 'POST',
    });
  },

  /**
   * Destination reached; driver completes the trip (IN_TRIP -> COMPLETED).
   * Calls POST /api/v1/trips/{tripId}/complete
   */
  completeTrip: async (tripId: string): Promise<TripResponse> => {
    return request<TripResponse>(`/api/v1/trips/${tripId}/complete`, {
      method: 'POST',
    });
  },
};
