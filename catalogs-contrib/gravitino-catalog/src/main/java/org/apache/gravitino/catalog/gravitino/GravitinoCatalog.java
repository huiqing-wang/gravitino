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
package org.apache.gravitino.catalog.gravitino;

import java.util.Map;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.PropertiesMetadata;

/**
 * A catalog implementation that connects to a remote Apache Gravitino instance, enabling federated
 * metadata access across regions and clouds.
 */
public class GravitinoCatalog extends BaseCatalog<GravitinoCatalog> {

  static final GravitinoCatalogPropertiesMetadata CATALOG_PROPERTIES_META =
      new GravitinoCatalogPropertiesMetadata();

  static final GravitinoEntityPropertiesMetadata ENTITY_PROPERTIES_META =
      new GravitinoEntityPropertiesMetadata();

  /**
   * @return The short name of the catalog provider, used in catalog creation API.
   */
  @Override
  public String shortName() {
    return "gravitino";
  }

  /**
   * Creates a new instance of {@link GravitinoCatalogOperations} with the provided configuration.
   *
   * @param config The configuration map for the Gravitino catalog operations.
   * @return A new instance of {@link GravitinoCatalogOperations}.
   */
  @Override
  protected CatalogOperations newOps(Map<String, String> config) {
    return new GravitinoCatalogOperations();
  }

  @Override
  public PropertiesMetadata catalogPropertiesMetadata() throws UnsupportedOperationException {
    return CATALOG_PROPERTIES_META;
  }

  @Override
  public PropertiesMetadata schemaPropertiesMetadata() throws UnsupportedOperationException {
    return ENTITY_PROPERTIES_META;
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return ENTITY_PROPERTIES_META;
  }
}
