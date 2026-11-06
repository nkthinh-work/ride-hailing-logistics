-- V4__payment_attempts_include_unknown_active_index.sql
-- M08 Group 4 P0 Safety Gate: Prevent multiple active/unresolved payment attempts when attempt is in UNKNOWN state

DROP INDEX IF EXISTS uk_payment_attempts_single_active;

CREATE UNIQUE INDEX uk_payment_attempts_single_active 
    ON payment_attempts (payment_id) 
    WHERE status IN ('PENDING', 'PROCESSING', 'UNKNOWN');
