package org.apache.gravitino.client;

import java.io.File;
import java.util.Optional;
import java.util.Properties;
import org.apache.gravitino.Config;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

public final class HTTPClientConfig extends Config {
  private static final String GRAVITINO_CONFIG = "gravitino.conf";
  public static final String GRAVITINO_CLIENT_CONFIG_PREFIX = "gravitino.client.http.";
  public static final int DEFAULT_MAX_TOTAL_CONNECTIONS = 25;
  public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 5;

  private int maxConnPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
  private int maxConnTotal = DEFAULT_MAX_TOTAL_CONNECTIONS;

  public static final ConfigEntry<Integer> MAX_CONN_PER_ROUTE =
      new ConfigBuilder("gravitino.client.http.maxConnPerRoute")
          .doc("The maximum number of threads in the thread pool used by Jetty webserver")
          .version(ConfigConstants.VERSION_0_8_0)
          .intConf()
          .checkValue(value -> value > 0, ConfigConstants.POSITIVE_NUMBER_ERROR_MSG)
          .createWithDefault(DEFAULT_MAX_CONNECTIONS_PER_ROUTE);

  public static final ConfigEntry<Integer> MAX_CONN_TOTAL =
      new ConfigBuilder("gravitino.client.http.maxConnTotal")
          .doc("The maximum number of threads in the thread pool used by Jetty webserver")
          .version(ConfigConstants.VERSION_0_8_0)
          .intConf()
          .checkValue(value -> value > 0, ConfigConstants.POSITIVE_NUMBER_ERROR_MSG)
          .createWithDefault(DEFAULT_MAX_TOTAL_CONNECTIONS);

  public static HTTPClientConfig loadConfig() {
    try {
      HTTPClientConfig clientConfig = new HTTPClientConfig();

      try {
        String confPath =
            Optional.ofNullable(System.getenv("GRAVITINO_CONF_DIR"))
                .orElse(
                    Optional.ofNullable(System.getenv("GRAVITINO_HOME"))
                        .map(s -> s + File.separator + "conf")
                        .orElse(null));

        if (confPath == null) {
          throw new IllegalArgumentException("GRAVITINO_CONF_DIR or GRAVITINO_HOME not set");
        }

        File confFile = new File(confPath + File.separator + GRAVITINO_CONFIG);
        if (!confFile.exists()) {
          throw new IllegalArgumentException(
              "Config file " + confFile.getAbsolutePath() + " not found");
        }

        Properties properties = clientConfig.loadPropertiesFromFile(confFile);
        clientConfig.loadFromProperties(properties);
      } catch (Exception exception) {
        throw new IllegalArgumentException("Failed to load conf from file ", exception);
      }
      return clientConfig;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Failed to load conf from file ", exception);
    }
  }

  public int getMaxConnPerRoute() {
    maxConnPerRoute = this.get(MAX_CONN_PER_ROUTE);

    return maxConnPerRoute;
  }

  public int getMaxConnTotal() {
    maxConnTotal = this.get(MAX_CONN_TOTAL);

    return maxConnTotal;
  }
}
