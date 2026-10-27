package com.example.txn;

import com.example.txn.input.CsvTransactionSource;
import com.example.txn.input.TransactionSource;
import com.example.txn.service.ScriptedFailureInjector;
import com.example.txn.service.TransactionProcessor;
import com.example.txn.store.InMemoryIdempotencyStore;
import com.example.txn.store.Ledger;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

/** Demo runner: java -jar target/idempotent-txn-processor-1.0.0.jar [path-to-csv] */
public final class App {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s %5$s%n");
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        root.addHandler(new StreamHandler(System.out, new SimpleFormatter()) {
            @Override public synchronized void publish(LogRecord r) { super.publish(r); flush(); }
        });

        Path csv = Path.of(args.length > 0 ? args[0] : "sample-data/transactions.csv");
        TransactionSource source = new CsvTransactionSource(csv);

        // Simulated downstream: T2 times out twice then recovers, T7 never recovers.
        ScriptedFailureInjector injector = new ScriptedFailureInjector().failTimes("T2", 2).failAlways("T7");

        TransactionProcessor processor =
                new TransactionProcessor(new InMemoryIdempotencyStore(), new Ledger(), injector, 3, 0);
        processor.processAll(source.read());

        System.out.println();
        System.out.print(processor.report().toText());
    }
}
