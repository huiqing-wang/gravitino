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
package org.apache.gravitino.catalog.wutong.operation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.TestJdbc;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.wutong.converter.WutongColumnDefaultValueConverter;
import org.apache.gravitino.catalog.wutong.converter.WutongExceptionConverter;
import org.apache.gravitino.catalog.wutong.converter.WutongTypeConverter;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

public class TestWutongDB extends TestJdbc {

  public static final String DEFAULT_POSTGRES_IMAGE = "postgres:13";

  @BeforeAll
  public static void startup() throws Exception {
    CONTAINER =
        new PostgreSQLContainer<>(DEFAULT_POSTGRES_IMAGE)
            .withDatabaseName(TEST_DB_NAME)
            .withUsername("root")
            .withPassword("root");
    DATABASE_OPERATIONS = new WutongSchemaOperations();
    JDBC_EXCEPTION_CONVERTER = new WutongExceptionConverter();
    TestJdbc.startup();
    String jdbcUrl = CONTAINER.getJdbcUrl();
    try {
      String database =
          new URI(CONTAINER.getJdbcUrl().substring(jdbcUrl.lastIndexOf("/") + 1, jdbcUrl.length()))
              .getPath();
      Map<String, String> config =
          new HashMap<String, String>() {
            {
              put(JdbcConfig.JDBC_DATABASE.getKey(), database);
            }
          };
      TABLE_OPERATIONS = new WutongTableOperations();
      DATABASE_OPERATIONS.initialize(DATA_SOURCE, JDBC_EXCEPTION_CONVERTER, config);
      TABLE_OPERATIONS.initialize(
          DATA_SOURCE,
          JDBC_EXCEPTION_CONVERTER,
          new WutongTypeConverter(),
          new WutongColumnDefaultValueConverter(),
          config);
      DATABASE_OPERATIONS.create(TEST_DB_NAME, null, null);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
}
