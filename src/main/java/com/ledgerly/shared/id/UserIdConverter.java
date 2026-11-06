package com.ledgerly.shared.id;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.UUID;

/** Stores a {@link UserId} as a plain UUID column. */
@Converter(autoApply = true)
public class UserIdConverter implements AttributeConverter<UserId, UUID> {

    @Override
    public UUID convertToDatabaseColumn(UserId id) {
        return id.value();
    }

    @Override
    public UserId convertToEntityAttribute(UUID value) {
        return new UserId(value);
    }
}
