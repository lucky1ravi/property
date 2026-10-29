-- Invoices created before state tracking take the state recorded on their lease's deposit.
update invoice i set state = d.state from deposit d where d.lease_id = i.lease_id and i.state is null and d.state is not null;
