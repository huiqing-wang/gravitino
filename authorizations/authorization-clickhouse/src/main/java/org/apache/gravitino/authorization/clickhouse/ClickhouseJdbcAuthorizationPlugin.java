package org.apache.gravitino.authorization.clickhouse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.jdbc.JdbcAuthorizationPlugin;

public class ClickhouseJdbcAuthorizationPlugin extends JdbcAuthorizationPlugin {
  private static volatile ClickhouseJdbcAuthorizationPlugin instance = null;

  private ClickhouseJdbcAuthorizationPlugin(Map<String, String> config) {
    super(config);
  }

  public static synchronized ClickhouseJdbcAuthorizationPlugin getInstance(Map<String, String> config) {
    if (instance == null) {
      synchronized (ClickhouseJdbcAuthorizationPlugin.class) {
        if (instance == null) {
          instance = new ClickhouseJdbcAuthorizationPlugin(config);
        }
      }
    }
    return instance;
  }

  @Override
  public List<String> getSetOwnerSQL(
      MetadataObject.Type type, String objectName, Owner preOwner, Owner newOwner) {
    return Collections.emptyList();
  }

  @Override
  public List<String> getCreateUserSQL(String username) {
    return Lists.newArrayList(String.format("CREATE USER IF NOT EXISTS %s", username));
  }

  @Override
  public List<String> getDropUserSQL(String username) {
    return Lists.newArrayList(String.format("DROP USER IF EXISTS %s", username));
  }

  @Override
  public List<String> getCreateRoleSQL(String roleName) {
    return Lists.newArrayList(String.format("CREATE ROLE IF NOT EXISTS %s", roleName));
  }

  @Override
  public List<String> getDropRoleSQL(String roleName) {
    return Lists.newArrayList(String.format("DROP ROLE IF EXISTS %s", roleName));
  }

  @Override
  public List<String> getGrantPrivilegeSQL(
          String privilege, String objectType, String objectName, String roleName) {
    return Lists.newArrayList(
            String.format("GRANT %s ON %s %s TO %s", privilege, objectType, objectName, roleName));
  }

  @Override
  public List<String> getRevokePrivilegeSQL(
          String privilege, String objectType, String objectName, String roleName) {
    return Lists.newArrayList(
            String.format(
                    "REVOKE %s ON %s %s FROM %s", privilege, objectType, objectName, roleName));
  }

  @Override
  public List<String> getGrantRoleSQL(String roleName, String grantorType, String grantorName) {
    return Lists.newArrayList(
            String.format("GRANT %s TO %s %s", roleName, grantorType, grantorName));
  }

  @Override
  public List<String> getRevokeRoleSQL(String roleName, String revokerType, String revokerName) {
    return Lists.newArrayList(
            String.format("REVOKE %s FROM %s %s", roleName, revokerType, revokerName));
  }
}
