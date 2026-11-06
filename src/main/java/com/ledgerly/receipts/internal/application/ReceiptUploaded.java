package com.ledgerly.receipts.internal.application;

import java.util.UUID;

/** Internal to receipts: a new file is stored and waits for its malware scan. */
public record ReceiptUploaded(UUID receiptId) {}
