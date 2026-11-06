package com.ledgerly.shared.id;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.UUID;

/** Stores a {@link TeamId} as a plain UUID column. */
@Converter(autoApply = true)
public class TeamIdConverter implements AttributeConverter<TeamId, UUID> {

    @Override
    public UUID convertToDatabaseColumn(TeamId id) {
        return id.value();
    }

    @Override
    public TeamId convertToEntityAttribute(UUID value) {
        return new TeamId(value);
    }
}
