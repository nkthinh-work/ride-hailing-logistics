-- V1__create_pricing_tables.sql
-- Database schema for Pricing Service (Independent DB according to CON-01)

CREATE TABLE pricing_rules (
    id UUID PRIMARY KEY,
    service_type VARCHAR(30) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,
    region_id VARCHAR(50) NOT NULL DEFAULT 'DEFAULT',
    version INT NOT NULL,
    base_fare NUMERIC(14, 2) NOT NULL,
    included_distance_km NUMERIC(8, 2) NOT NULL DEFAULT 2.00,
    price_per_km NUMERIC(14, 2) NOT NULL,
    price_per_minute NUMERIC(14, 2) NOT NULL,
    minimum_fare NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,

    CONSTRAINT ck_pricing_rules_service_type
        CHECK (service_type IN ('RIDE', 'DELIVERY')),

    CONSTRAINT ck_pricing_rules_vehicle_type
        CHECK (vehicle_type IN ('MOTORBIKE', 'CAR_4_SEAT', 'CAR_7_SEAT')),

    CONSTRAINT ck_pricing_rules_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),

    CONSTRAINT ck_pricing_rules_effective_dates
        CHECK (effective_to IS NULL OR effective_to > effective_from),

    CONSTRAINT ck_pricing_rules_fares_positive
        CHECK (base_fare >= 0 AND included_distance_km >= 0 AND price_per_km >= 0 AND price_per_minute >= 0 AND minimum_fare >= 0),

    CONSTRAINT uk_pricing_rules_version
        UNIQUE (service_type, vehicle_type, region_id, version)
);

CREATE INDEX idx_pricing_rules_lookup
    ON pricing_rules(service_type, vehicle_type, region_id, status, effective_from);

-- Audit Trail for Pricing Rule Changes (FR-PRI-012)
CREATE TABLE pricing_rule_audits (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    version INT NOT NULL,
    action VARCHAR(30) NOT NULL,
    actor_id UUID NOT NULL,
    reason VARCHAR(255),
    snapshot TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_pricing_rule_audits_rule
        FOREIGN KEY (rule_id) REFERENCES pricing_rules(id) ON DELETE CASCADE,

    CONSTRAINT ck_pricing_rule_audits_action
        CHECK (action IN ('RULE_CREATED', 'RULE_PUBLISHED', 'RULE_ARCHIVED'))
);

CREATE INDEX idx_pricing_rule_audits_rule
    ON pricing_rule_audits(rule_id, created_at);
