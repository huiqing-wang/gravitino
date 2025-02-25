package org.apache.gravitino.authorization.postgreqsql;

import java.util.Map;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.authorization.BaseAuthorization;

public class PostgresqlJdbcAuthorization extends BaseAuthorization<PostgresqlJdbcAuthorization> {
  public PostgresqlJdbcAuthorization() {}

  @Override
  public String shortName() {
    return "postgresql";
  }

  @Override
  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> config) {
    return PostgresqlJdbcAuthorizationPlugin.getInstance(config);
  }
}
