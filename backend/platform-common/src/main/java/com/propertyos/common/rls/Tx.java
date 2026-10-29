package com.propertyos.common.rls;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs work in a transaction so row-level security settings are applied. Every database
 * read must happen in a transaction; outside one, RLS policies return no rows.
 */
@Component
public class Tx {
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public Tx(PlatformTransactionManager tm) {
        this.write = new TransactionTemplate(tm);
        this.read = new TransactionTemplate(tm);
        this.read.setReadOnly(true);
    }

    /**
     * Runs work in a read-only transaction.
     *
     * @param work the work to run
     * @param <T>  the result type
     * @return whatever {@code work} returns
     */
    public <T> T read(Supplier<T> work) {
        return read.execute(s -> work.get());
    }

    /**
     * Runs work in a read-write transaction that commits on success and rolls back on an exception.
     *
     * @param work the work to run
     * @param <T>  the result type
     * @return whatever {@code work} returns
     */
    public <T> T write(Supplier<T> work) {
        return write.execute(s -> work.get());
    }

    /**
     * Runs work that returns nothing in a read-write transaction.
     *
     * @param work the work to run
     */
    public void run(Runnable work) {
        write.executeWithoutResult(s -> work.run());
    }
}
