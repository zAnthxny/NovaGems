package net.watones.novacoins.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public final class MySqlStorageProvider extends JdbcStorageProvider {
  private final HikariDataSource source;
  private final int workerThreads;

  public MySqlStorageProvider(
      String host,
      int port,
      String database,
      String username,
      String password,
      int poolSize,
      boolean ssl) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("NovaCoins-MySQL");
    config.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
    config.setJdbcUrl(
        "jdbc:mysql://"
            + host
            + ":"
            + port
            + "/"
            + database
            + "?useSSL="
            + ssl
            + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8");
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(10_000);
    config.setMaxLifetime(1_800_000);
    source = new HikariDataSource(config);
    workerThreads = Math.max(1, Math.min(poolSize, 8));
  }

  @Override
  protected DataSource dataSource() {
    return source;
  }

  @Override
  protected String idColumn() {
    return "BIGINT PRIMARY KEY AUTO_INCREMENT";
  }

  @Override
  protected boolean supportsSelectForUpdate() {
    return true;
  }

  @Override
  public int workerThreads() {
    return workerThreads;
  }

  @Override
  public String description() {
    return "MySQL/MariaDB";
  }

  @Override
  public void close() {
    source.close();
  }
}
