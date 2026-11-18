-- Gym Management System schema for PostgreSQL 14+ / 18+
-- Includes Admin Portal and Member Portal tables

CREATE TABLE IF NOT EXISTS admin (
  admin_id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS members (
  member_id BIGSERIAL PRIMARY KEY,
  full_name VARCHAR(120) NOT NULL,
  age SMALLINT NOT NULL CHECK (age >= 10 AND age <= 100),
  gender VARCHAR(20) NOT NULL,
  phone VARCHAR(25) NOT NULL UNIQUE,
  email VARCHAR(254) NULL UNIQUE,
  address VARCHAR(500) NOT NULL,
  joining_date DATE NOT NULL,
  emergency_contact VARCHAR(25) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION update_members_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_members_updated_at ON members;
CREATE TRIGGER trg_members_updated_at
BEFORE UPDATE ON members
FOR EACH ROW EXECUTE FUNCTION update_members_timestamp();

CREATE TABLE IF NOT EXISTS member_accounts (
  account_id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL UNIQUE REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE CASCADE,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role = 'MEMBER'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TRIGGER IF EXISTS trg_member_accounts_updated_at ON member_accounts;
CREATE TRIGGER trg_member_accounts_updated_at
BEFORE UPDATE ON member_accounts
FOR EACH ROW EXECUTE FUNCTION update_members_timestamp();

CREATE TABLE IF NOT EXISTS membership_plans (
  plan_id BIGSERIAL PRIMARY KEY,
  plan_name VARCHAR(80) NOT NULL UNIQUE,
  duration_months SMALLINT NOT NULL CHECK (duration_months IN (1,3,6,12)),
  price NUMERIC(10,2) NOT NULL CHECK (price >= 0),
  description VARCHAR(500) NULL,
  membership_type VARCHAR(40) NOT NULL DEFAULT 'Standard',
  benefits TEXT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS membership_type VARCHAR(40) NOT NULL DEFAULT 'Standard';
ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS benefits TEXT NULL;

CREATE TABLE IF NOT EXISTS member_memberships (
  membership_id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  plan_id BIGINT NOT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL CHECK (end_date >= start_date),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED','CANCELLED','UPCOMING')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mm_member ON member_memberships(member_id);
CREATE INDEX IF NOT EXISTS idx_mm_dates ON member_memberships(start_date, end_date);

CREATE TABLE IF NOT EXISTS payments (
  payment_id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  membership_id BIGINT NULL REFERENCES member_memberships(membership_id) ON UPDATE CASCADE ON DELETE SET NULL,
  amount NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('CASH','UPI','CARD','RAZORPAY')),
  payment_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(20) NOT NULL DEFAULT 'PAID' CHECK (status IN ('PAID','PENDING','FAILED','REFUNDED')),
  notes VARCHAR(500) NULL
);

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN ('PAID','PENDING','FAILED','REFUNDED'));

CREATE INDEX IF NOT EXISTS idx_payments_member_date ON payments(member_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_payments_date_status ON payments(payment_date, status);

CREATE TABLE IF NOT EXISTS membership_upgrade_requests (
  request_id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE CASCADE,
  current_membership_id BIGINT NULL REFERENCES member_memberships(membership_id) ON UPDATE CASCADE ON DELETE SET NULL,
  current_plan_id BIGINT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE SET NULL,
  requested_plan_id BIGINT NOT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  price_difference NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (price_difference >= 0),
  reason VARCHAR(500) NULL,
  status VARCHAR(25) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','COMPLETED','CANCELLED')),
  admin_notes VARCHAR(500) NULL,
  reviewed_by VARCHAR(50) NULL,
  reviewed_at TIMESTAMPTZ NULL,
  payment_id BIGINT NULL REFERENCES payments(payment_id) ON UPDATE CASCADE ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TRIGGER IF EXISTS trg_upgrade_requests_updated_at ON membership_upgrade_requests;
CREATE TRIGGER trg_upgrade_requests_updated_at
BEFORE UPDATE ON membership_upgrade_requests
FOR EACH ROW EXECUTE FUNCTION update_members_timestamp();

CREATE INDEX IF NOT EXISTS idx_upgrade_req_member ON membership_upgrade_requests(member_id, status);
CREATE INDEX IF NOT EXISTS idx_upgrade_req_status ON membership_upgrade_requests(status, created_at DESC);

CREATE TABLE IF NOT EXISTS razorpay_transactions (
  payment_id BIGINT PRIMARY KEY REFERENCES payments(payment_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  order_id VARCHAR(40) NOT NULL UNIQUE,
  gateway_payment_id VARCHAR(50) NULL UNIQUE,
  verified_signature CHAR(64) NULL,
  target_plan_id BIGINT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE SET NULL,
  purpose VARCHAR(30) NOT NULL DEFAULT 'PAYMENT',
  upgrade_request_id BIGINT NULL REFERENCES membership_upgrade_requests(request_id) ON UPDATE CASCADE ON DELETE SET NULL,
  failure_reason VARCHAR(500) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS target_plan_id BIGINT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS purpose VARCHAR(30) NOT NULL DEFAULT 'PAYMENT';
ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS upgrade_request_id BIGINT NULL REFERENCES membership_upgrade_requests(request_id) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500) NULL;

CREATE TABLE IF NOT EXISTS attendance (
  attendance_id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  check_in_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  attendance_date DATE GENERATED ALWAYS AS (CAST(check_in_at AS DATE)) STORED,
  CONSTRAINT uq_attendance_member_day UNIQUE (member_id, attendance_date)
);

CREATE INDEX IF NOT EXISTS idx_attendance_date ON attendance(attendance_date);

CREATE TABLE IF NOT EXISTS notifications (
  notification_id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE CASCADE,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(160) NOT NULL,
  message VARCHAR(600) NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  dedupe_key VARCHAR(120) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_member ON notifications(member_id, is_read, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_dedupe ON notifications(member_id, dedupe_key) WHERE dedupe_key IS NOT NULL;

INSERT INTO membership_plans(plan_name, duration_months, price, description, membership_type, benefits) VALUES
  ('Monthly', 1, 1500.00, 'One month of full gym floor access', 'Basic', 'Full cardio & strength floor access, Locker room & showers, Free fitness orientation'),
  ('Quarterly', 3, 4000.00, 'Three months of gym access with group sessions', 'Standard', 'Full gym & cardio access, Group HIIT & mobility classes, Monthly body composition check, Locker & shower access'),
  ('Half-Yearly', 6, 7500.00, 'Six months of gym access plus personal guidance', 'Premium', 'Unlimited gym & group classes, 2 personal training check-ins/month, Custom nutrition guide, Priority equipment booking'),
  ('Yearly', 12, 14000.00, 'Twelve months of all-access VIP club membership', 'Elite', '24/7 VIP gym & studio access, Dedicated personal coach sessions, Sauna & recovery zone, Free guest passes (4/year), Priority renewal rate')
ON CONFLICT (plan_name) DO UPDATE SET
  duration_months = EXCLUDED.duration_months,
  price = EXCLUDED.price,
  description = EXCLUDED.description,
  membership_type = CASE WHEN membership_plans.membership_type = 'Standard' OR membership_plans.membership_type IS NULL THEN EXCLUDED.membership_type ELSE membership_plans.membership_type END,
  benefits = COALESCE(membership_plans.benefits, EXCLUDED.benefits);
