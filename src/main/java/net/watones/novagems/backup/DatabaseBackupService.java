package net.watones.novagems.backup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Keeps one database snapshot per calendar day and deletes the oldest beyond {@code keepDays}.
 *
 * <p>It checks hourly whether today's file exists, so it produces exactly one backup a day whether
 * or not the server restarts. Each snapshot is written to a temporary name and renamed only once
 * complete, so a crash or shutdown mid-copy can never leave a truncated file that looks like a good
 * backup; the leftover temporary file is removed on the next pass.
 */
public final class DatabaseBackupService implements AutoCloseable {
  private static final String PREFIX = "novagems-";
  private static final String EXTENSION = ".db";
  static final String TEMP_SUFFIX = ".tmp";
  private static final Pattern BACKUP_NAME =
      Pattern.compile("novagems-\\d{4}-\\d{2}-\\d{2}\\.db");
  private static final Pattern TEMP_NAME =
      Pattern.compile("novagems-\\d{4}-\\d{2}-\\d{2}\\.db\\.tmp");

  @FunctionalInterface
  public interface Snapshotter {
    void snapshotTo(Path target) throws Exception;
  }

  private final Snapshotter snapshotter;
  private final Path directory;
  private final int keepDays;
  private final Clock clock;
  private final Consumer<String> info;
  private final BiConsumer<String, Throwable> failure;
  private ScheduledExecutorService scheduler;

  public DatabaseBackupService(
      Snapshotter snapshotter,
      Path directory,
      int keepDays,
      Clock clock,
      Consumer<String> info,
      BiConsumer<String, Throwable> failure) {
    if (keepDays < 1) throw new IllegalArgumentException("keepDays must be at least 1");
    this.snapshotter = snapshotter;
    this.directory = directory;
    this.keepDays = keepDays;
    this.clock = clock;
    this.info = info;
    this.failure = failure;
  }

  public synchronized void start(long initialDelaySeconds, long periodSeconds) {
    if (scheduler != null) return;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("NovaGems-Backup").daemon(true).factory());
    scheduler.scheduleWithFixedDelay(
        this::runSafely, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);
  }

  private void runSafely() {
    // An exception escaping a scheduled task silently cancels every future run.
    try {
      runIfDue();
    } catch (Throwable error) {
      failure.accept("No se pudo crear el respaldo automático de la base de datos", error);
    }
  }

  /** Creates today's snapshot if it is missing, then prunes. Returns the file it created. */
  Optional<Path> runIfDue() throws Exception {
    Files.createDirectories(directory);
    String name = fileName(LocalDate.now(clock));
    Path target = directory.resolve(name);
    Optional<Path> created = Optional.empty();
    if (!Files.exists(target)) {
      Path temp = directory.resolve(name + TEMP_SUFFIX);
      Files.deleteIfExists(temp);
      snapshotter.snapshotTo(temp);
      moveIntoPlace(temp, target);
      info.accept(
          "Respaldo de la base de datos creado: backups/" + name
              + " (" + humanSize(Files.size(target)) + ")");
      created = Optional.of(target);
    }
    prune();
    return created;
  }

  /** Deletes backups beyond the newest {@code keepDays} and any leftover temporary files. */
  void prune() throws IOException {
    List<Path> backups = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (BACKUP_NAME.matcher(name).matches()) backups.add(entry);
        else if (TEMP_NAME.matcher(name).matches()) Files.deleteIfExists(entry);
      }
    }
    // ISO dates sort chronologically as plain strings.
    backups.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
    for (int index = keepDays; index < backups.size(); index++) {
      Files.deleteIfExists(backups.get(index));
    }
  }

  static String fileName(LocalDate day) {
    return PREFIX + day.format(DateTimeFormatter.ISO_LOCAL_DATE) + EXTENSION;
  }

  private static void moveIntoPlace(Path temp, Path target) throws IOException {
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String humanSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
    return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  @Override
  public synchronized void close() {
    if (scheduler == null) return;
    scheduler.shutdown();
    try {
      // Let a copy that is already running finish instead of leaving only a temporary file.
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
    } catch (InterruptedException interrupted) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
