import type {
  DriverLocationResponse,
  LocationUpdateRequest,
  NearbyDriver,
} from '../types';
import { request } from './apiClient';

export const locationService = {
  turnOnline: async (
    longitude: number,
    latitude: number,
    selectedVehicleId?: string
  ): Promise<DriverLocationResponse> => {
    const payload: LocationUpdateRequest = { longitude, latitude, selectedVehicleId };
    return request<DriverLocationResponse>('/api/v1/locations/me/online', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  updateLocation: async (
    longitude: number,
    latitude: number,
    sessionId?: string,
    sequence?: number
  ): Promise<DriverLocationResponse> => {
    const payload: LocationUpdateRequest = { longitude, latitude, sessionId, sequence };
    return request<DriverLocationResponse>('/api/v1/locations/me', {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },

  turnOffline: async (): Promise<DriverLocationResponse> => {
    return request<DriverLocationResponse>('/api/v1/locations/me/offline', {
      method: 'POST',
    });
  },

  getMyLocation: async (): Promise<DriverLocationResponse> => {
    return request<DriverLocationResponse>('/api/v1/locations/me');
  },

  getNearbyDrivers: async (
    longitude: number,
    latitude: number,
    radiusKm = 3,
    limit = 10
  ): Promise<NearbyDriver[]> => {
    return request<NearbyDriver[]>(
      `/api/v1/locations/nearby?longitude=${longitude}&latitude=${latitude}&radiusKm=${radiusKm}&limit=${limit}`
    );
  },
};
