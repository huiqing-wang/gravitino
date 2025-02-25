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
package org.apache.gravitino.catalog.wutong;

import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcCatalog;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.wutong.converter.WutongColumnDefaultValueConverter;
import org.apache.gravitino.catalog.wutong.converter.WutongExceptionConverter;
import org.apache.gravitino.catalog.wutong.converter.WutongTypeConverter;
import org.apache.gravitino.catalog.wutong.operation.WutongSchemaOperations;
import org.apache.gravitino.catalog.wutong.operation.WutongTableOperations;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.capability.Capability;

public class WutongCatalog extends JdbcCatalog {

  @Override
  public String shortName() {
    return "jdbc-wutong";
  }

  @Override
  protected CatalogOperations newOps(Map<String, String> config) {
    JdbcTypeConverter jdbcTypeConverter = createJdbcTypeConverter();
    return new WutongCatalogOperations(
        createExceptionConverter(),
        jdbcTypeConverter,
        createJdbcDatabaseOperations(),
        createJdbcTableOperations(),
        createJdbcColumnDefaultValueConverter());
  }

  @Override
  public Capability newCapability() {
    return new WutongCatalogCapability();
  }

  @Override
  protected JdbcExceptionConverter createExceptionConverter() {
    return new WutongExceptionConverter();
  }

  @Override
  protected JdbcTypeConverter createJdbcTypeConverter() {
    return new WutongTypeConverter();
  }

  @Override
  protected JdbcDatabaseOperations createJdbcDatabaseOperations() {
    return new WutongSchemaOperations();
  }

  @Override
  protected JdbcTableOperations createJdbcTableOperations() {
    return new WutongTableOperations();
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new WutongColumnDefaultValueConverter();
  }
}
