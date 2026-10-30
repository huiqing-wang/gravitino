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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.jdbc.JdbcAuthorizationProperties;
import org.apache.gravitino.exceptions.AuthorizationPluginException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DorisJdbcAuthorizationPlugin} SQL generation. */
public class TestDorisJdbcAuthorizationPlugin {

  private static final Map<String, String> PROPERTIES =
      ImmutableMap.of(
          JdbcAuthorizationProperties.JDBC_URL,
          "jdbc:h2:mem:doris_auth_test",
          JdbcAuthorizationProperties.JDBC_USERNAME,
          "xx",
          JdbcAuthorizationProperties.JDBC_PASSWORD,
          "xx",
          JdbcAuthorizationProperties.JDBC_DRIVER,
          "org.h2.Driver");

  private DorisJdbcAuthorizationPlugin plugin;

  @BeforeEach
  public void setUp() throws Exception {
    Field instanceField = DorisJdbcAuthorizationPlugin.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);

    Constructor<DorisJdbcAuthorizationPlugin> constructor =
        DorisJdbcAuthorizationPlugin.class.getDeclaredConstructor(Map.class);
    constructor.setAccessible(true);
    plugin = constructor.newInstance(PROPERTIES);
  }

  // ========== User management ==========

  @Test
  public void testCreateUserSQL() {
    List<String> sqls = plugin.getCreateUserSQL("jack");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("CREATE USER IF NOT EXISTS 'jack'@'%'", sqls.get(0));
  }

  @Test
  public void testDropUserSQL() {
    List<String> sqls = plugin.getDropUserSQL("test");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("DROP USER IF EXISTS 'test'@'%'", sqls.get(0));
  }

  @Test
  public void testCreateUserSQLForGroup() {
    List<String> sqls = plugin.getCreateUserSQL("GRAVITINO_GROUP_engineers");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals(
        "CREATE USER IF NOT EXISTS 'GRAVITINO_GROUP_engineers'@'%'", sqls.get(0));
  }

  // ========== Role management ==========

  @Test
  public void testCreateRoleSQL() {
    List<String> sqls = plugin.getCreateRoleSQL("bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("CREATE ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testDropRoleSQL() {
    List<String> sqls = plugin.getDropRoleSQL("bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("DROP ROLE bi_reader", sqls.get(0));
  }

  // ========== Privilege grant/revoke (TO ROLE / FROM ROLE) ==========

  @Test
  public void testGrantSelectOnTable() {
    List<String> sqls = plugin.getGrantPrivilegeSQL("SELECT", "TABLE", "bi_db.orders", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT SELECT_PRIV ON bi_db.orders TO ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testGrantSelectOnSchemaTables() {
    // TABLE type with wildcard: "bi_db.*"
    List<String> sqls = plugin.getGrantPrivilegeSQL("SELECT", "TABLE", "bi_db.*", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT SELECT_PRIV ON bi_db.* TO ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testGrantSelectOnAll() {
    // TABLE type with global wildcard: "*.*"
    List<String> sqls = plugin.getGrantPrivilegeSQL("SELECT", "TABLE", "*.*", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT SELECT_PRIV ON *.* TO ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testGrantCreateOnSchemaAppendsWildcard() {
    // SCHEMA type: "bi_db" → "bi_db.*"
    List<String> sqls = plugin.getGrantPrivilegeSQL("CREATE", "SCHEMA", "bi_db", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT CREATE_PRIV ON bi_db.* TO ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testGrantCreateOnAllSchemas() {
    // SCHEMA type with wildcard: "*" → "*.*"
    List<String> sqls = plugin.getGrantPrivilegeSQL("CREATE", "SCHEMA", "*", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT CREATE_PRIV ON *.* TO ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testGrantInsertMapsToLoadPriv() {
    List<String> sqls = plugin.getGrantPrivilegeSQL("INSERT", "TABLE", "bi_db.orders", "writer");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT LOAD_PRIV ON bi_db.orders TO ROLE writer", sqls.get(0));
  }

  @Test
  public void testGrantAlterPriv() {
    List<String> sqls = plugin.getGrantPrivilegeSQL("ALTER", "TABLE", "bi_db.orders", "developer");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT ALTER_PRIV ON bi_db.orders TO ROLE developer", sqls.get(0));
  }

  @Test
  public void testGrantDeleteMapsToLoadPriv() {
    // Doris has no DELETE_PRIV; DELETE data is governed by LOAD_PRIV.
    List<String> sqls = plugin.getGrantPrivilegeSQL("DELETE", "TABLE", "bi_db.orders", "developer");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT LOAD_PRIV ON bi_db.orders TO ROLE developer", sqls.get(0));
  }

  @Test
  public void testGrantUpdateMapsToLoadPriv() {
    // Doris has no UPDATE_PRIV; UPDATE data is governed by LOAD_PRIV.
    List<String> sqls = plugin.getGrantPrivilegeSQL("UPDATE", "TABLE", "bi_db.orders", "developer");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT LOAD_PRIV ON bi_db.orders TO ROLE developer", sqls.get(0));
  }

  @Test
  public void testGrantDropPriv() {
    List<String> sqls = plugin.getGrantPrivilegeSQL("DROP", "TABLE", "bi_db.orders", "admin_role");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT DROP_PRIV ON bi_db.orders TO ROLE admin_role", sqls.get(0));
  }

  @Test
  public void testGrantUsageOnSchemaIsSkipped() {
    // Doris USAGE_PRIV is only valid on CATALOG/RESOURCE, never on a database, so the
    // USE_SCHEMA -> USAGE translation must be dropped instead of emitting invalid SQL.
    List<String> sqls = plugin.getGrantPrivilegeSQL("USAGE", "SCHEMA", "bi_db", "hive_user");
    Assertions.assertTrue(sqls.isEmpty());
  }

  @Test
  public void testRevokeUsageOnSchemaIsSkipped() {
    List<String> sqls = plugin.getRevokePrivilegeSQL("USAGE", "SCHEMA", "bi_db", "hive_user");
    Assertions.assertTrue(sqls.isEmpty());
  }

  @Test
  public void testGrantUsageOnCatalogIsKept() {
    // USAGE on a CATALOG object is valid in Doris and must not be dropped.
    List<String> sqls = plugin.getGrantPrivilegeSQL("USAGE", "CATALOG", "hive_ctl", "hive_user");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT USAGE_PRIV ON hive_ctl TO ROLE hive_user", sqls.get(0));
  }

  @Test
  public void testRevokeSelectOnTable() {
    List<String> sqls =
        plugin.getRevokePrivilegeSQL("SELECT", "TABLE", "bi_db.orders", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("REVOKE SELECT_PRIV ON bi_db.orders FROM ROLE bi_reader", sqls.get(0));
  }

  @Test
  public void testRevokeCreateOnSchema() {
    List<String> sqls = plugin.getRevokePrivilegeSQL("CREATE", "SCHEMA", "bi_db", "bi_reader");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("REVOKE CREATE_PRIV ON bi_db.* FROM ROLE bi_reader", sqls.get(0));
  }

  // ========== Role grant/revoke to user ==========

  @Test
  public void testGrantRoleToUser() {
    List<String> sqls = plugin.getGrantRoleSQL("bi_reader", "USER", "jack");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT 'bi_reader' TO 'jack'@'%'", sqls.get(0));
  }

  @Test
  public void testGrantRoleToGroup() {
    List<String> sqls = plugin.getGrantRoleSQL("bi_reader", "USER", "GRAVITINO_GROUP_engineers");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("GRANT 'bi_reader' TO 'GRAVITINO_GROUP_engineers'@'%'", sqls.get(0));
  }

  @Test
  public void testRevokeRoleFromUser() {
    List<String> sqls = plugin.getRevokeRoleSQL("bi_reader", "USER", "test");
    Assertions.assertEquals(1, sqls.size());
    Assertions.assertEquals("REVOKE 'bi_reader' FROM 'test'@'%'", sqls.get(0));
  }

  // ========== Owner ==========

  @Test
  public void testSetOwnerSQLReturnsEmpty() {
    List<String> sqls =
        plugin.getSetOwnerSQL(MetadataObject.Type.TABLE, "bi_db.orders", null, null);
    Assertions.assertTrue(sqls.isEmpty());
  }

  // ========== Singleton & provider ==========

  @Test
  public void testSingletonReturnsSameInstance() {
    DorisJdbcAuthorizationPlugin first = DorisJdbcAuthorizationPlugin.getInstance(PROPERTIES);
    DorisJdbcAuthorizationPlugin second = DorisJdbcAuthorizationPlugin.getInstance(PROPERTIES);
    Assertions.assertSame(first, second);
  }

  @Test
  public void testAuthorizationShortName() {
    DorisJdbcAuthorization authorization = new DorisJdbcAuthorization();
    Assertions.assertEquals("doris", authorization.shortName());
  }

  // ========== Error surfacing ==========

  @Test
  public void testRealDorisErrorIsSurfaced() {
    SQLException se = new SQLException("Unknown privilege USAGE_PRIV", "HY000", 1105);
    AuthorizationPluginException ex = plugin.toAuthorizationPluginException(se);
    String message = ex.getMessage();
    Assertions.assertTrue(message.contains("1105"), message);
    Assertions.assertTrue(message.contains("HY000"), message);
    Assertions.assertTrue(message.contains("Unknown privilege USAGE_PRIV"), message);
  }
}
