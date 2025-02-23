package org.apache.gravitino.authorization.wutong;

import java.util.Map;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.authorization.BaseAuthorization;

public class WutongJdbcAuthorization extends BaseAuthorization<WutongJdbcAuthorization> {
  public WutongJdbcAuthorization() {}

  @Override
  public String shortName() {
    return "wutong";
  }

  @Override
  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> config) {
    return WutongJdbcAuthorizationPlugin.getInstance(config);
  }
}
