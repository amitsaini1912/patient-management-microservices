-- Seed data for analytics.
-- ORDER MATTERS: patients first, then their visits, then the claims.
-- A foreign key forces the parent row to exist before the child row.

-- 1) Patients (fresh UUIDs so this also runs on the existing k8s database)
INSERT INTO patient (id, name, email, address, date_of_birth, registered_date) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Priya Sharma',  'priya.sharma@example.com',  '12 MG Road, Pune',        '1990-04-12', '2025-01-05'),
  ('22222222-2222-2222-2222-222222222222', 'Rahul Verma',   'rahul.verma@example.com',   '5 Park Street, Kolkata',  '1985-09-23', '2025-01-08'),
  ('33333333-3333-3333-3333-333333333333', 'Anjali Gupta',  'anjali.gupta@example.com',  '88 Residency Rd, Delhi',  '1997-12-01', '2025-01-15');

-- 2) Visits (each belongs to a patient above). Spread across departments and months.
INSERT INTO visit (id, patient_id, visit_date, department) VALUES
  ('a0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '2025-01-10', 'Cardiology'),
  ('a0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '2025-02-15', 'Cardiology'),
  ('a0000000-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', '2025-01-20', 'Orthopedics'),
  ('a0000000-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', '2025-03-05', 'Neurology'),
  ('a0000000-0000-0000-0000-000000000005', '33333333-3333-3333-3333-333333333333', '2025-02-28', 'Cardiology'),
  ('a0000000-0000-0000-0000-000000000006', '33333333-3333-3333-3333-333333333333', '2025-03-18', 'Orthopedics');

-- 3) Claims (each belongs to a visit above). Mix of statuses; some visits have 2 claims.
INSERT INTO claim (id, visit_id, claim_amount, paid_amount, status) VALUES
  ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',  5000.00, 4000.00, 'APPROVED'),
  ('c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001',  2000.00,    0.00, 'DENIED'),
  ('c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002',  3000.00, 3000.00, 'APPROVED'),
  ('c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000003',  8000.00, 6000.00, 'APPROVED'),
  ('c0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000004', 10000.00,    0.00, 'DENIED'),
  ('c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000005',  4500.00, 4500.00, 'APPROVED'),
  ('c0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000006',  6000.00,    0.00, 'PENDING'),
  ('c0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000006',  1500.00, 1500.00, 'APPROVED');
