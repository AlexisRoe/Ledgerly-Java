package com.ledgerly.imports.internal.application;

import com.ledgerly.imports.internal.infrastructure.ImportJobs;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.error.TooManyRequests;
import com.ledgerly.shared.error.UnsupportedMedia;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.io.CappedInputStream;
import com.ledgerly.shared.storage.ObjectStorage;
import com.ledgerly.teams.TeamsFacade;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a CSV for import. Everything that can be refused cheaply is refused here, before a job exists: not a
 * teammate, daily limit used up, too big, not text, wrong header, too many rows. Only then is the file stored and
 * a QUEUED import saved; the job itself starts after the transaction commits.
 */
@Service
public class UploadImport {

    static final List<String> REQUIRED_HEADER = List.of("date", "merchant", "amount", "currency", "category");

    /**
     * The uploaded file's stream is consumed (and capped) by {@link #execute}; {@code originalName} is display metadata only.
     */
    public record Command(TeamId teamId, UserId caller, String originalName, InputStream content) {}

    private final TeamsFacade teams;
    private final ImportJobs jobs;
    private final ObjectStorage storage;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final LedgerlyLimits limits;
    private final Clock clock;

    UploadImport(
            TeamsFacade teams,
            ImportJobs jobs,
            ObjectStorage storage,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            LedgerlyLimits limits,
            Clock clock) {
        this.teams = teams;
        this.jobs = jobs;
        this.storage = storage;
        this.events = events;
        this.tx = tx;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Validates and stores the CSV, saves a QUEUED import and returns its id; the job starts after commit.
     *
     * @throws com.ledgerly.shared.error.NotFound if the caller is not a teammate
     */
    public UUID execute(Command c) {
        UUID team = c.teamId().value();
        UUID user = c.caller().value();
        teams.roleOf(c.teamId(), c.caller()).orElseThrow(NotFound::new);
        // A quick refusal before reading the upload; the authoritative check below runs under a lock.
        requireUnderDailyLimit(team, user);

        Path temp = null;
        String key = "imports/" + UUID.randomUUID();
        boolean stored = false;
        try {
            temp = Files.createTempFile("import-", ".csv");
            try (InputStream in =
                    new CappedInputStream(c.content(), limits.maxUploadSize().toBytes())) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            int rows = inspect(temp);
            storage.put(key, temp, "text/csv");
            stored = true;

            UUID id = UUID.randomUUID();
            Instant now = clock.instant();
            tx.executeWithoutResult(status -> {
                jobs.lockUserInTeam(team, user);
                requireUnderDailyLimit(team, user);
                jobs.insert(
                        new ImportJobs.Job(id, team, user, "QUEUED", displayName(c.originalName()), key, rows, 0, now));
                events.publishEvent(new ImportQueued(id));
            });
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            if (stored) {
                storage.delete(key);
            }
            throw e;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the OS cleans temp files; the content is the user's own data
                }
            }
        }
    }

    /** 5 imports per user per team per UTC day. "Today" comes from the injected clock. */
    private void requireUnderDailyLimit(UUID team, UUID user) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (jobs.countSince(team, user, startOfDay) >= limits.maxImportsPerDay()) {
            Instant nextMidnight =
                    today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            throw new TooManyRequests(
                    "At most " + limits.maxImportsPerDay() + " imports per team per day",
                    Duration.between(now, nextMidnight));
        }
    }

    /**
     * Reads the staged file once: it must be UTF-8 text, start with the expected header, and have between 1 and
     * {@code maxImportRows} data rows (blank lines do not count). Returns the row count.
     */
    private int inspect(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int rows = 0;
        try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            String header = reader.readLine();
            if (header == null) {
                throw new InvalidRequest("file", "The file is empty");
            }
            requireHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.indexOf('\0') >= 0) {
                    throw new UnsupportedMedia("The file is not a CSV text file");
                }
                if (!line.isBlank()) {
                    rows++;
                    if (rows > limits.maxImportRows()) {
                        throw new InvalidRequest("file", "At most " + limits.maxImportRows() + " rows per import");
                    }
                }
            }
        } catch (CharacterCodingException e) {
            throw new UnsupportedMedia("The file must be UTF-8 text");
        }
        if (rows == 0) {
            throw new InvalidRequest("file", "The file has no data rows");
        }
        return rows;
    }

    private static void requireHeader(String header) {
        String stripped = header.startsWith("﻿") ? header.substring(1) : header; // a spreadsheet may add a BOM
        if (stripped.indexOf('\0') >= 0) {
            throw new UnsupportedMedia("The file is not a CSV text file");
        }
        List<String> columns = java.util.Arrays.stream(stripped.split(",", -1))
                .map(c -> c.strip().toLowerCase(java.util.Locale.ROOT))
                .toList();
        boolean ok = columns.equals(REQUIRED_HEADER)
                || columns.equals(
                        java.util.stream.Stream.concat(REQUIRED_HEADER.stream(), java.util.stream.Stream.of("note"))
                                .toList());
        if (!ok) {
            throw new InvalidRequest("file", "The first line must be: date,merchant,amount,currency,category[,note]");
        }
    }

    private static String displayName(String original) {
        String name = original.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "import.csv";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
