/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.authorization.doris;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.jdbc.JdbcAuthorizationPlugin;
import org.apache.gravitino.exceptions.AuthorizationPluginException;

/**
 * DorisJdbcAuthorizationPlugin is the JDBC-based authorization plugin for Apache Doris.
 *
 * <p>Doris speaks the MySQL wire protocol but has its own SQL dialect for access control:
 *
 * <ul>
 *   <li>User identities are quoted as {@code 'user'@'host'}
 *   <li>Privilege names carry a {@code _PRIV} suffix (e.g. {@code SELECT_PRIV})
 *   <li>Object references use {@code db.tbl}, {@code db.*}, or {@code *.*} (internal catalog)
 *   <li>Granting privileges to a role requires the {@code TO ROLE} keyword
 *   <li>Granting a role to a user uses {@code GRANT 'role' TO 'user'@'%'}
 * </ul>
 */
public class DorisJdbcAuthorizationPlugin extends JdbcAuthorizationPlugin {

  /**
   * Doris identifies accounts by {@code 'user'@'host'}. Gravitino only carries a bare user/group
   * name, so we default the host to the wildcard {@code %} to accept connections from any host.
   */
  private static final String USER_IDENTITY_FORMAT = "'%s'@'%%'";

  /**
   * Maps the generic JDBC privilege names (from {@link
   * org.apache.gravitino.authorization.jdbc.JdbcPrivilege}) to Doris-specific privilege keywords.
   *
   * <p>Doris only supports a fixed set of privilege types ({@code SELECT_PRIV}, {@code LOAD_PRIV},
   * {@code ALTER_PRIV}, {@code CREATE_PRIV}, {@code DROP_PRIV}, {@code USAGE_PRIV}, {@code
   * GRANT_PRIV}, {@code ADMIN_PRIV}). There is no dedicated {@code DELETE_PRIV}, {@code
   * UPDATE_PRIV} or {@code INSERT_PRIV}: data-writing statements (INSERT / UPDATE / DELETE / LOAD)
   * are all governed by {@code LOAD_PRIV}. Emitting an unknown keyword such as {@code DELETE_PRIV}
   * makes Doris fail with {@code Unknown privilege type}, so every write operation must collapse to
   * {@code LOAD_PRIV}.
   */
  private static final Map<String, String> PRIVILEGE_MAPPING =
      ImmutableMap.<String, String>builder()
          .put("SELECT", "SELECT_PRIV")
          .put("INSERT", "LOAD_PRIV")
          .put("UPDATE", "LOAD_PRIV")
          .put("DELETE", "LOAD_PRIV")
          .put("ALTER", "ALTER_PRIV")
          .put("CREATE", "CREATE_PRIV")
          .put("DROP", "DROP_PRIV")
          .put("USAGE", "USAGE_PRIV")
          .build();

  private static volatile DorisJdbcAuthorizationPlugin instance = null;

  private DorisJdbcAuthorizationPlugin(Map<String, String> config) {
    super(config);
  }

  /**
   * Get the singleton instance of the Doris authorization plugin.
   *
   * @param config the JDBC authorization properties used on first initialization
   * @return the shared {@link DorisJdbcAuthorizationPlugin} instance
   */
  public static synchronized DorisJdbcAuthorizationPlugin getInstance(Map<String, String> config) {
    if (instance == null) {
      synchronized (DorisJdbcAuthorizationPlugin.class) {
        if (instance == null) {
          instance = new DorisJdbcAuthorizationPlugin(config);
        }
      }
    }
    return instance;
  }

  @Override
  public List<String> getSetOwnerSQL(
      MetadataObject.Type type, String objectName, Owner preOwner, Owner newOwner) {
    // Doris has no ALTER OBJECT ... SET OWNER statement; ownership is modelled via ALL privileges.
    return Collections.emptyList();
  }

  @Override
  public List<String> getCreateUserSQL(String username) {
    return Lists.newArrayList(
        String.format("CREATE USER IF NOT EXISTS " + USER_IDENTITY_FORMAT, username));
  }

  @Override
  public List<String> getDropUserSQL(String username) {
    return Lists.newArrayList(
        String.format("DROP USER IF EXISTS " + USER_IDENTITY_FORMAT, username));
  }

  @Override
  public List<String> getCreateRoleSQL(String roleName) {
    return Lists.newArrayList(String.format("CREATE ROLE %s", roleName));
  }

  @Override
  public List<String> getDropRoleSQL(String roleName) {
    return Lists.newArrayList(String.format("DROP ROLE %s", roleName));
  }

  @Override
  public List<String> getGrantPrivilegeSQL(
      String privilege, String objectType, String objectName, String roleName) {
    if (isUnsupportedDatabaseUsage(privilege, objectType)) {
      return Lists.newArrayList();
    }
    return Lists.newArrayList(
        String.format(
            "GRANT %s ON %s TO ROLE %s",
            toDorisPrivilege(privilege), toDorisObjectName(objectType, objectName), roleName));
  }

  @Override
  public List<String> getRevokePrivilegeSQL(
      String privilege, String objectType, String objectName, String roleName) {
    if (isUnsupportedDatabaseUsage(privilege, objectType)) {
      return Lists.newArrayList();
    }
    return Lists.newArrayList(
        String.format(
            "REVOKE %s ON %s FROM ROLE %s",
            toDorisPrivilege(privilege), toDorisObjectName(objectType, objectName), roleName));
  }

  @Override
  public List<String> getGrantRoleSQL(String roleName, String grantorType, String grantorName) {
    return Lists.newArrayList(
        String.format("GRANT '%s' TO " + USER_IDENTITY_FORMAT, roleName, grantorName));
  }

  @Override
  public List<String> getRevokeRoleSQL(String roleName, String revokerType, String revokerName) {
    return Lists.newArrayList(
        String.format("REVOKE '%s' FROM " + USER_IDENTITY_FORMAT, roleName, revokerName));
  }

  /**
   * Doris returns the generic MySQL error code {@code 1105} for many access-control failures and
   * puts the actionable reason in the exception message. The base implementation discards the
   * message and keeps only the error code, which makes GRANT/REVOKE failures impossible to
   * diagnose, so we surface the real Doris message and SQL state here.
   *
   * @param se the SQL exception thrown while executing a Doris statement
   * @return an {@link AuthorizationPluginException} carrying the real Doris error detail
   */
  @Override
  protected AuthorizationPluginException toAuthorizationPluginException(SQLException se) {
    return new AuthorizationPluginException(
        "Doris authorization plugin failed to execute SQL, error code: %d, SQL state: %s, message: %s",
        se.getErrorCode(), se.getSQLState(), se.getMessage());
  }

  /**
   * Convert a generic JDBC privilege name to the Doris-specific keyword. Falls back to appending
   * {@code _PRIV} for any privilege not explicitly mapped.
   */
  private String toDorisPrivilege(String privilege) {
    return PRIVILEGE_MAPPING.getOrDefault(privilege, privilege + "_PRIV");
  }

  /**
   * In Doris, {@code USAGE_PRIV} is only valid on {@code CATALOG} and {@code RESOURCE} objects; it
   * cannot be granted on a database. Gravitino's {@code USE_SCHEMA} privilege is translated by
   * {@link org.apache.gravitino.authorization.jdbc.JdbcSecurableObjectMappingProvider} into a
   * {@code USAGE} privilege on a SCHEMA object, so emitting {@code USAGE_PRIV ON db.*} would be
   * rejected by Doris (error 1105). Database access in Doris is implied by holding table-level
   * privileges, so we simply drop this unsupported combination instead of generating invalid SQL.
   *
   * @param privilege the generic JDBC privilege name
   * @param objectType the {@link MetadataObject.Type} name reported by the mapping provider
   * @return true when the privilege/object pair cannot be expressed in Doris and must be skipped
   */
  private boolean isUnsupportedDatabaseUsage(String privilege, String objectType) {
    return "USAGE".equals(privilege) && MetadataObject.Type.SCHEMA.name().equals(objectType);
  }

  /**
   * Translate the {@code objectType}/{@code objectName} pair produced by {@link
   * org.apache.gravitino.authorization.jdbc.JdbcSecurableObject} into the object reference Doris
   * expects in a GRANT/REVOKE statement.
   *
   * <p>TABLE-level objects already carry the fully qualified {@code db.tbl} (or {@code *.*} /
   * {@code db.*}) form. SCHEMA-level objects only carry the database name, so we append {@code .*}
   * to make the privilege apply to every table inside that database.
   *
   * @param objectType the {@link MetadataObject.Type} name reported by the mapping provider
   * @param objectName the full name reported by the mapping provider
   * @return the object reference to embed in a Doris GRANT/REVOKE statement
   */
  private String toDorisObjectName(String objectType, String objectName) {
    if (MetadataObject.Type.SCHEMA.name().equals(objectType) && !objectName.endsWith(".*")) {
      return objectName + ".*";
    }
    return objectName;
  }
}
