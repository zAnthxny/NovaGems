package net.watones.novacoins.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.watones.novacoins.economy.EconomyOperation;
import net.watones.novacoins.economy.MutationKind;
import net.watones.novacoins.economy.TransactionStatus;
import net.watones.novacoins.economy.TransactionType;

/**
 * Blocking journal primitive. Production code must access it only through {@link JournalWriter}.
 * One startup scan builds the runtime index; batch reads never rescan the directory.
 */
public class RecoveryJournal implements AutoCloseable {
  private static final int MAGIC = 0x4E_43_4F_50;
  private static final int FORMAT_V1 = 1;
  private static final int FORMAT_V2 = 2;
  private static final Comparator<EconomyOperation> ACCOUNT_ORDER =
      Comparator.comparingLong(EconomyOperation::accountSequence)
          .thenComparing(EconomyOperation::createdAt)
          .thenComparing(EconomyOperation::operationId);

  private final Path directory;
  private final Map<UUID, EconomyOperation> pending = new HashMap<>();
  private final NavigableMap<UUID, NavigableSet<EconomyOperation>> pendingByAccount =
      new TreeMap<>();
  private final List<CorruptRecoveryRecord> corrupt = new ArrayList<>();
  private UUID batchCursor;
  private int lastBatchTraversalSteps;
  private boolean initialized;

  public RecoveryJournal(Path directory) { this.directory = directory; }

  public synchronized void initialize() throws IOException {
    Files.createDirectories(directory);
    Path corruptDirectory = directory.resolve("corrupt");
    Files.createDirectories(corruptDirectory);
    corrupt.clear();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(corruptDirectory, "*.corrupt")) {
      for (Path path : stream) {
        Instant detected;
        try { detected = Files.getLastModifiedTime(path).toInstant(); }
        catch (IOException unavailableTime) { detected = Instant.EPOCH; }
        corrupt.add(new CorruptRecoveryRecord(path.getFileName().toString(), detected,
            "Registro en cuarentena; requiere revisión manual"));
      }
    }
    recoverTemporaries();
    pending.clear();
    pendingByAccount.clear();
    batchCursor = null;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.op")) {
      for (Path path : stream) {
        try {
          EconomyOperation operation = read(path);
          index(operation);
        } catch (Exception damaged) {
          quarantineSafely(path, damaged);
        }
      }
    }
    initialized = true;
  }

  private void recoverTemporaries() throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tmp")) {
      for (Path temporary : stream) {
        try {
          EconomyOperation operation = read(temporary);
          Path target = path(operation.operationId());
          if (Files.exists(target)) Files.deleteIfExists(temporary);
          else moveIntoPlace(temporary, target);
        } catch (Exception partial) {
          quarantineSafely(temporary, partial);
        }
      }
    }
  }

  public synchronized void store(EconomyOperation operation) throws IOException {
    ensureInitialized();
    Path target = path(operation.operationId());
    if (pending.containsKey(operation.operationId())) return;
    Path temporary = directory.resolve(operation.operationId() + ".tmp");
    try (FileOutputStream file = new FileOutputStream(temporary.toFile());
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
      output.writeInt(MAGIC);
      output.writeInt(FORMAT_V2);
      output.writeUTF(operation.operationId().toString());
      output.writeUTF(operation.accountId().toString());
      output.writeUTF(operation.kind().name());
      output.writeLong(operation.amount());
      output.writeUTF(operation.type().name());
      output.writeUTF(operation.reason());
      output.writeUTF(operation.reference());
      output.writeUTF(operation.initialStatus().name());
      output.writeLong(operation.createdAt().toEpochMilli());
      output.writeLong(operation.accountSequence());
      output.flush();
      file.getChannel().force(true);
    }
    moveIntoPlace(temporary, target);
    forceDirectory();
    index(operation);
  }

  public synchronized void remove(UUID operationId) throws IOException {
    ensureInitialized();
    Files.deleteIfExists(path(operationId));
    forceDirectory();
    EconomyOperation removed = pending.remove(operationId);
    if (removed != null) {
      pendingByAccount.computeIfPresent(removed.accountId(), (ignored, operations) -> {
        operations.remove(removed);
        return operations.isEmpty() ? null : operations;
      });
      if (pendingByAccount.isEmpty()) batchCursor = null;
    }
  }

  public synchronized List<EconomyOperation> loadBatch(int limit) throws IOException {
    ensureInitialized();
    if (limit < 1) throw new IllegalArgumentException("Batch limit must be positive");
    if (pendingByAccount.isEmpty()) return List.of();
    List<EconomyOperation> batch = new ArrayList<>(Math.min(limit, pending.size()));
    Map<UUID, Iterator<EconomyOperation>> iterators = new HashMap<>();
    UUID account = nextAccount(batchCursor);
    int emptyAccounts = 0;
    lastBatchTraversalSteps = 0;
    while (batch.size() < limit && emptyAccounts < pendingByAccount.size()) {
      lastBatchTraversalSteps++;
      UUID currentAccount = account;
      Iterator<EconomyOperation> iterator = iterators.computeIfAbsent(
          currentAccount, ignored -> pendingByAccount.get(currentAccount).iterator());
      if (iterator.hasNext()) {
        batch.add(iterator.next());
        batchCursor = account;
        emptyAccounts = 0;
      } else {
        emptyAccounts++;
      }
      account = nextAccount(account);
    }
    return List.copyOf(batch);
  }

  public synchronized List<EconomyOperation> loadAll() throws IOException {
    ensureInitialized();
    List<EconomyOperation> operations = new ArrayList<>(pending.size());
    for (NavigableSet<EconomyOperation> accountOperations : pendingByAccount.values()) {
      operations.addAll(accountOperations);
    }
    return List.copyOf(operations);
  }

  public synchronized int pendingCount() throws IOException {
    ensureInitialized();
    return pending.size();
  }

  public synchronized int corruptCount() throws IOException {
    ensureInitialized();
    return corrupt.size();
  }

  public synchronized int pendingCount(UUID accountId) throws IOException {
    ensureInitialized();
    NavigableSet<EconomyOperation> operations = pendingByAccount.get(accountId);
    return operations == null ? 0 : operations.size();
  }

  public synchronized List<CorruptRecoveryRecord> corruptRecords() throws IOException {
    ensureInitialized();
    return List.copyOf(corrupt);
  }

  private void ensureInitialized() throws IOException { if (!initialized) initialize(); }

  protected final synchronized void index(EconomyOperation operation) {
    EconomyOperation previous = pending.putIfAbsent(operation.operationId(), operation);
    if (previous != null) return;
    pendingByAccount.computeIfAbsent(operation.accountId(), ignored -> new TreeSet<>(ACCOUNT_ORDER))
        .add(operation);
  }

  public synchronized int lastBatchTraversalSteps() { return lastBatchTraversalSteps; }

  private UUID nextAccount(UUID after) {
    UUID next = after == null ? pendingByAccount.firstKey() : pendingByAccount.higherKey(after);
    return next == null ? pendingByAccount.firstKey() : next;
  }

  private EconomyOperation read(Path path) throws IOException {
    try (DataInputStream input =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (input.readInt() != MAGIC) throw new IOException("Invalid journal magic");
      int format = input.readInt();
      if (format != FORMAT_V1 && format != FORMAT_V2) {
        throw new IOException("Unsupported journal format " + format);
      }
      UUID operationId = UUID.fromString(input.readUTF());
      UUID accountId = UUID.fromString(input.readUTF());
      MutationKind kind = MutationKind.valueOf(input.readUTF());
      long amount = input.readLong();
      TransactionType type = TransactionType.valueOf(input.readUTF());
      String reason = input.readUTF();
      String reference = input.readUTF();
      TransactionStatus status = TransactionStatus.valueOf(input.readUTF());
      Instant created = Instant.ofEpochMilli(input.readLong());
      // v1.1.1 had no sequence. Timestamp is the migration seed; UUID is only a tie breaker for
      // legacy records whose original relative order can no longer be reconstructed.
      long sequence = format == FORMAT_V2 ? input.readLong() : Math.max(1, created.toEpochMilli());
      return new EconomyOperation(operationId, accountId, kind, amount, type, reason, reference,
          status, created, sequence);
    } catch (IllegalArgumentException corruptValue) {
      throw new IOException("Invalid recovery record", corruptValue);
    }
  }

  private void quarantine(Path source, Exception error) throws IOException {
    Path corruptDirectory = directory.resolve("corrupt");
    Files.createDirectories(corruptDirectory);
    String original = source.getFileName().toString();
    Path target = corruptDirectory.resolve(original + ".corrupt");
    int suffix = 1;
    while (Files.exists(target)) {
      target = corruptDirectory.resolve(original + "." + suffix++ + ".corrupt");
    }
    Files.move(source, target);
    corrupt.add(new CorruptRecoveryRecord(target.getFileName().toString(), Instant.now(),
        summarize(error)));
  }

  private void quarantineSafely(Path source, Exception error) {
    try {
      quarantine(source, error);
    } catch (IOException quarantineFailure) {
      corrupt.add(new CorruptRecoveryRecord(source.getFileName().toString(), Instant.now(),
          summarize(quarantineFailure)));
    }
  }

  private String summarize(Exception error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
    return message.length() <= 160 ? message : message.substring(0, 160);
  }

  private Path path(UUID operationId) { return directory.resolve(operationId + ".op"); }

  private void moveIntoPlace(Path temporary, Path target) throws IOException {
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target);
    } catch (java.nio.file.FileAlreadyExistsException duplicate) {
      Files.deleteIfExists(temporary);
    }
  }

  private void forceDirectory() throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (java.nio.file.AccessDeniedException | UnsupportedOperationException unsupported) {
      // Some providers do not expose directory fsync. The record file itself was forced and the
      // move was atomic, so keep the portable durability guarantee available.
    }
  }

  /** No persistent handle is retained; the hook makes shutdown ordering directly testable. */
  @Override public void close() throws IOException {}
}
