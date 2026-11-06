package com.ledgerly.shared.role;

/** A rank within a team. The ladder is MEMBER &lt; APPROVER &lt; OWNER; higher ranks include lower ones. */
public enum Role {
    MEMBER,
    APPROVER,
    OWNER;

    /** True if this role has at least the rank of {@code other} (OWNER includes APPROVER and MEMBER). */
    public boolean includes(Role other) {
        return compareTo(other) >= 0;
    }
}
