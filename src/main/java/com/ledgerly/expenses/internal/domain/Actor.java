package com.ledgerly.expenses.internal.domain;

import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.util.Objects;

/** Who is acting, with their role in the expense's team. The domain receives the role; it never looks it up. */
public record Actor(UserId id, Role role) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
    }
}
