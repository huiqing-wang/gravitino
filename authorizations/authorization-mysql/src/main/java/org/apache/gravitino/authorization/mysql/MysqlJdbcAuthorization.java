package org.apache.gravitino.authorization.mysql;

import java.util.Map;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.authorization.BaseAuthorization;

public class MysqlJdbcAuthorization extends BaseAuthorization<MysqlJdbcAuthorization> {
  public MysqlJdbcAuthorization() {}

  @Override
  public String shortName() {
    return "mysql";
  }

  @Override
  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> config) {
    return MysqlJdbcAuthorizationPlugin.getInstance(config);
  }
}
