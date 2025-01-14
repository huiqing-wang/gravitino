package org.apache.gravitino.authorization.clickhouse;

import java.util.Map;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.authorization.BaseAuthorization;

public class ClickhouseJdbcAuthorization extends BaseAuthorization<ClickhouseJdbcAuthorization> {
  public ClickhouseJdbcAuthorization() {}

  @Override
  public String shortName() {
    return "mysql";
  }

  @Override
  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> config) {
    return ClickhouseJdbcAuthorizationPlugin.getInstance(config);
  }
}
