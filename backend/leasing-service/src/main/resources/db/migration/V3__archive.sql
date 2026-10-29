-- Owners, properties and units are archived rather than deleted, so leases, invoices and history keep pointing at them.
alter table owner    add column archived_at timestamptz;
alter table property add column archived_at timestamptz;
alter table unit     add column archived_at timestamptz;
