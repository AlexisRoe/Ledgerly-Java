package com.ledgerly.imports.internal.web;

import com.ledgerly.imports.events.ImportCompleted;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Test listener standing in for the audit module (M9). */
@Component
@Profile("test")
public class CapturedImportEvents {

    public final List<ImportCompleted> completed = new CopyOnWriteArrayList<>();

    @EventListener
    void on(ImportCompleted event) {
        completed.add(event);
    }
}
