-- Why a company was suspended, by whom and when, so the operator and the audit trail can explain it.
alter table organization add column status_reason text;
alter table organization add column status_changed_by varchar(120);
alter table organization add column status_changed_at timestamptz;
