
CREATE TABLE refresh_sessions (
                                  id UUID PRIMARY KEY,

                                  user_id UUID NOT NULL,

                                  token_hash VARCHAR(64) NOT NULL UNIQUE,

                                  created_at TIMESTAMPTZ NOT NULL,

                                  expires_at TIMESTAMPTZ NOT NULL,

                                  revoked_at TIMESTAMPTZ,

                                  CONSTRAINT fk_refresh_sessions_user
                                      FOREIGN KEY (user_id)
                                          REFERENCES users(id)
);

CREATE INDEX idx_refresh_sessions_user_id
    ON refresh_sessions(user_id);

CREATE INDEX idx_refresh_sessions_expires_at
    ON refresh_sessions(expires_at);
