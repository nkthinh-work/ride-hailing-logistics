// Core Types for Ride-Hailing & Logistics Platform

export type RoleCode =
  | 'CUSTOMER'
  | 'DRIVER'
  | 'REVIEWER'
  | 'SUPPORT_STAFF'
  | 'FINANCE_STAFF'
  | 'ADMINISTRATOR';

export type PermissionCode =
  | 'TRIP_CREATE'
  | 'TRIP_VIEW_OWN'
  | 'TRIP_ACCEPT'
  | 'LOCATION_UPDATE'
  | 'DRIVER_REVIEW'
  | 'PAYMENT_REFUND'
  | 'ROLE_MANAGE';

export type DriverStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type LiveDriverStatus = 'ONLINE' | 'OFFLINE';

export interface UserResponse {
  id: string;
  email: string;
  status: string;
  roles: RoleCode[];
  permissions: PermissionCode[];
  createdAt?: string;
  userId?: string;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface DecodedJwt {
  sub: string;
  iss?: string;
  roles: RoleCode[];
  permissions: PermissionCode[];
  exp: number;
  iat: number;
  email?: string;
}

export interface DriverApplicationResponse {
  id: string;
  userId: string;
  fullName: string;
  phone: string;
  driverLicenseNumber: string;
  vehiclePlate: string;
  status: DriverStatus;
  rejectionReason?: string | null;
  createdAt: string;
  reviewedAt?: string | null;
}

export interface DriverEligibilityResponse {
  userId: string;
  eligible: boolean;
  accountStatus: string;
  applicationStatus: DriverStatus | null;
  hasDriverRole: boolean;
  reasons: string[];
}

export interface DriverApplicationRequest {
  fullName: string;
  phone: string;
  driverLicenseNumber: string;
  vehiclePlate: string;
}

export interface RejectDriverRequest {
  reason: string;
}

export interface DriverLocationResponse {
  driverId: string;
  longitude: number;
  latitude: number;
  status: LiveDriverStatus;
  updatedAt?: string;
  lastUpdatedAt?: string;
  sessionId?: string;
  selectedVehicleId?: string;
}

export interface LocationUpdateRequest {
  longitude: number;
  latitude: number;
  sessionId?: string;
  sequence?: number;
  selectedVehicleId?: string;
}

export interface NearbyDriver {
  driverId: string;
  distanceKm: number;
  longitude: number;
  latitude: number;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  path?: string;
  timestamp?: string;
  errors?: Record<string, string[]>;
}

export interface ApiLogEntry {
  id: string;
  timestamp: string;
  method: string;
  url: string;
  status: number;
  durationMs: number;
  requestBody?: unknown;
  responseBody?: unknown;
  correlationId: string;
  success: boolean;
}

export * from './realtime.ts';
export * from './pricing.ts';
export * from './payment.ts';
