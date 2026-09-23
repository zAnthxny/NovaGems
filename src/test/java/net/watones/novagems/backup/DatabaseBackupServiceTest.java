package net.watones.novagems.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseBackupServiceTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atTime(3, 0).toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));

  @TempDir Path directory;
  private final List<String> logs = new ArrayList<>();
  private final AtomicInteger snapshots = new AtomicInteger();

  private DatabaseBackupService service(int keepDays) {
    return new DatabaseBackupService(
        target -> {
          snapshots.incrementAndGet();
          Files.writeString(target, "snapshot");
        },
        directory,
        keepDays,
        CLOCK,
        logs::add,
        (message, error) -> logs.add("FAIL " + message));
  }

  @Test
  void createsTodaysBackupOnceNoMatterHowOftenItChecks() throws Exception {
    DatabaseBackupService backups = service(7);

    assertThat(backups.runIfDue()).contains(directory.resolve("novagems-2026-09-23.db"));
    assertThat(backups.runIfDue()).isEmpty();
    assertThat(backups.runIfDue()).isEmpty();

    assertThat(snapshots).hasValue(1);
    assertThat(Files.readString(directory.resolve("novagems-2026-09-23.db"))).isEqualTo("snapshot");
  }

  @Test
  void keepsOnlyTheNewestDaysAndNeverTouchesUnrelatedFiles() throws Exception {
    for (int daysAgo = 1; daysAgo <= 9; daysAgo++) {
      Files.writeString(
          directory.resolve(DatabaseBackupService.fileName(TODAY.minusDays(daysAgo))), "old");
    }
    Files.writeString(directory.resolve("notas.txt"), "keep me");
    Files.writeString(directory.resolve("novagems-manual.db"), "keep me");

    service(7).runIfDue();

    assertThat(backupNames())
        .containsExactly(
            "novagems-2026-09-17.db",
            "novagems-2026-09-18.db",
            "novagems-2026-09-19.db",
            "novagems-2026-09-20.db",
            "novagems-2026-09-21.db",
            "novagems-2026-09-22.db",
            "novagems-2026-09-23.db");
    assertThat(directory.resolve("notas.txt")).exists();
    assertThat(directory.resolve("novagems-manual.db")).exists();
  }

  @Test
  void aFailedCopyLeavesNoBackupThatLooksGoodAndTheNextCheckRetries() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    DatabaseBackupService backups =
        new DatabaseBackupService(
            target -> {
              Files.writeString(target, "partial");
              if (attempts.incrementAndGet() == 1) throw new IllegalStateException("disk full");
            },
            directory,
            7,
            CLOCK,
            logs::add,
            (message, error) -> {});

    assertThatThrownBy(backups::runIfDue).hasMessage("disk full");
    assertThat(directory.resolve("novagems-2026-09-23.db")).doesNotExist();

    assertThat(backups.runIfDue()).isPresent();
    assertThat(directory.resolve("novagems-2026-09-23.db")).exists();
    assertThat(directory.resolve("novagems-2026-09-23.db.tmp")).doesNotExist();
  }

  @Test
  void removesTemporaryFilesLeftByACrashDuringAPreviousCopy() throws Exception {
    Files.writeString(directory.resolve("novagems-2026-09-20.db.tmp"), "half written");

    service(7).runIfDue();

    assertThat(directory.resolve("novagems-2026-09-20.db.tmp")).doesNotExist();
  }

  @Test
  void rejectsAZeroRetention() {
    assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private List<String> backupNames() throws Exception {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.matches("novagems-\\d{4}-\\d{2}-\\d{2}\\.db"))
          .sorted()
          .toList();
    }
  }
}
