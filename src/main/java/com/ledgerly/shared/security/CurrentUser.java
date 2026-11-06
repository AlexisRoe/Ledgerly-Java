package com.ledgerly.shared.security;

import com.ledgerly.shared.id.UserId;

/** The authenticated caller of the current request. */
public interface CurrentUser {

    /** @throws com.ledgerly.shared.error.Unauthenticated when nobody is authenticated */
    UserId id();
}
