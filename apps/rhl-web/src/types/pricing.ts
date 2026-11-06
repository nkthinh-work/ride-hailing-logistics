// Pricing Types matching Pricing Service (M04 / M05)

export type ServiceType = 'RIDE' | 'DELIVERY';
export type VehicleType = 'MOTORBIKE' | 'CAR_4_SEAT' | 'CAR_7_SEAT';

export interface FareBreakdown {
  baseFare: number;
  distanceFare: number;
  timeFare: number;
  surgeMultiplier: number;
  surgeAmount: number;
  tollFees?: number;
  discountAmount?: number;
  totalFare: number;
}

export interface FareQuoteRequest {
  serviceType: ServiceType;
  vehicleType: VehicleType;
  pickupLatitude: number;
  pickupLongitude: number;
  pickupAddress?: string;
  dropoffLatitude: number;
  dropoffLongitude: number;
  dropoffAddress?: string;
  regionId?: string;
}

export interface FareQuoteResponse {
  quoteId: string;
  customerId: string;
  serviceType: ServiceType;
  vehicleType: VehicleType;
  regionId?: string;
  pickupLatitude: number;
  pickupLongitude: number;
  pickupAddress?: string;
  dropoffLatitude: number;
  dropoffLongitude: number;
  dropoffAddress?: string;
  distanceMeters: number;
  durationSeconds: number;
  routeProvider?: string;
  isSimulated?: boolean;
  pricingRuleId?: string;
  pricingRuleVersion?: string;
  breakdown?: FareBreakdown;
  totalFare: number;
  currency: string;
  createdAt: string;
  expiresAt: string;
  isExpired?: boolean;
  isSurgeApplied?: boolean;
}

export interface CreateTripRequest {
  quoteId: string;
  idempotencyKey: string;
  serviceType: ServiceType;
  vehicleType: VehicleType;
  customerConfirmedSurge: boolean;
  pickupLatitude?: number;
  pickupLongitude?: number;
  dropoffLatitude?: number;
  dropoffLongitude?: number;
}
