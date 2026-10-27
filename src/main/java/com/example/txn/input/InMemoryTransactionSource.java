package com.example.txn.input;

import com.example.txn.model.TransactionRequest;
import java.util.List;

public final class InMemoryTransactionSource implements TransactionSource {
    private final List<TransactionRequest> requests;

    public InMemoryTransactionSource(List<TransactionRequest> requests) {
        this.requests = List.copyOf(requests);
    }

    @Override
    public List<TransactionRequest> read() { return requests; }
}
