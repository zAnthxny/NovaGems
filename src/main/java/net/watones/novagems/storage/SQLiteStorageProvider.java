package net.watones.novagems.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public final class SQLiteStorageProvider extends JdbcStorageProvider {
  private final SQLiteDataSource source;
  private final Path file;

  public SQLiteStorageProvider(Path file) {
    this.file = file;
    SQLiteConfig config = new SQLiteConfig();
    config.setBusyTimeout(10_000);
    config.enforceForeignKeys(true);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
    source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
  }

  @Override
  public void initialize() throws Exception {
    Files.createDirectories(file.toAbsolutePath().getParent());
    super.initialize();
    try (Connection c = source.getConnection();
        Statement s = c.createStatement()) {
      s.execute("PRAGMA busy_timeout=10000");
    }
  }

  @Override
  protected DataSource dataSource() {
    return source;
  }

  @Override
  protected String idColumn() {
    return "INTEGER PRIMARY KEY AUTOINCREMENT";
  }

  @Override
  public int workerThreads() {
    return 1;
  }

  @Override
  public boolean supportsSnapshot() {
    return true;
  }

  /**
   * VACUUM INTO reads through its own connection's snapshot, so in WAL mode it never blocks the
   * economy writer, and it includes changes still sitting in the -wal file. The result is a
   * single self-contained file that needs no -wal/-shm companions to be restored.
   */
  @Override
  public void snapshotTo(Path target) throws Exception {
    try (Connection connection = source.getConnection();
        PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
      statement.setString(1, target.toAbsolutePath().toString());
      statement.execute();
    }
  }

  @Override
  public String description() {
    return "SQLite (" + file.getFileName() + ")";
  }

  @Override
  public void close() {}
}
