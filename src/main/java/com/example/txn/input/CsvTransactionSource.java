package com.example.txn.input;

import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Header: transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber,originalTransactionId
 * Unparseable cells become null so the validator rejects that record alone; the batch keeps going.
 */
public final class CsvTransactionSource implements TransactionSource {
    private final Path path;

    public CsvTransactionSource(Path path) { this.path = path; }

    @Override
    public List<TransactionRequest> read() throws IOException {
        List<TransactionRequest> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(path);
        for (int i = 1; i < lines.size(); i++) {           // skip header
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] c = line.split(",", -1);
            out.add(new TransactionRequest(
                    cell(c, 0), cell(c, 1), cell(c, 2),
                    parseType(cell(c, 3)), parseAmount(cell(c, 4)), cell(c, 5),
                    parseLong(cell(c, 6)), cell(c, 7)));
        }
        return out;
    }

    private static String cell(String[] c, int i) {
        return i < c.length && !c[i].isBlank() ? c[i].trim() : null;
    }

    private static TransactionType parseType(String s) {
        try { return s == null ? null : TransactionType.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal parseAmount(String s) {
        try { return s == null ? null : new BigDecimal(s); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parseLong(String s) {
        try { return s == null ? null : Long.valueOf(s); }
        catch (NumberFormatException e) { return null; }
    }
}
