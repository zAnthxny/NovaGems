package net.watones.novacoins.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
  public String description() {
    return "SQLite (" + file.getFileName() + ")";
  }

  @Override
  public void close() {}
}
