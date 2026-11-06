package com.ledgerly.receipts.internal.domain;

/** Malware-check state: PENDING_SCAN, then ACCEPTED (clean) or QUARANTINED (infected). */
public enum ScanStatus {
    PENDING_SCAN,
    ACCEPTED,
    QUARANTINED
}
