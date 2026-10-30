-- Keep deleted companies for audit and recovery, while removing them from normal operation.
alter table organization add column deleted_at timestamptz;
alter table organization add column deleted_by varchar(120);

create index ix_organization_status on organization (status);
