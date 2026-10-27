package com.example.txn.input;

import com.example.txn.model.TransactionRequest;
import java.io.IOException;
import java.util.List;

/** Replaceable input boundary: could be backed by a queue consumer, DB poller or REST endpoint. */
public interface TransactionSource {
    List<TransactionRequest> read() throws IOException;
}
