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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.connector.BaseCatalogPropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/** Properties metadata for the Gravitino federated catalog. */
public class GravitinoCatalogPropertiesMetadata extends BaseCatalogPropertiesMetadata {

  /** The URI of the remote Gravitino server. */
  public static final String GRAVITINO_URI = "gravitino.uri";

  /** The metalake name on the remote Gravitino server. */
  public static final String GRAVITINO_METALAKE = "gravitino.metalake";

  /** The catalog name on the remote Gravitino server. Defaults to the local catalog name. */
  public static final String GRAVITINO_CATALOG = "gravitino.catalog";

  /** The authentication type for connecting to the remote Gravitino server. */
  public static final String GRAVITINO_AUTH_TYPE = "gravitino.auth.type";

  /** The username for simple authentication. */
  public static final String GRAVITINO_AUTH_USERNAME = "gravitino.auth.username";

  /** The password for basic authentication. */
  public static final String GRAVITINO_AUTH_PASSWORD = "gravitino.auth.password";

  /** Whether to enable local caching of remote metadata. */
  public static final String GRAVITINO_CACHE_ENABLED = "gravitino.cache.enabled";

  /** The TTL for cached metadata entries. */
  public static final String GRAVITINO_CACHE_TTL = "gravitino.cache.ttl";

  /** The maximum number of entries in the metadata cache. */
  public static final String GRAVITINO_CACHE_MAX_SIZE = "gravitino.cache.max-size";

  private static final Map<String, PropertyEntry<?>> GRAVITINO_CATALOG_PROPERTY_ENTRIES =
      ImmutableMap.<String, PropertyEntry<?>>builder()
          .put(
              GRAVITINO_URI,
              PropertyEntry.stringRequiredPropertyEntry(
                  GRAVITINO_URI,
                  "The URI of the remote Gravitino server (e.g., http://host:8090)",
                  true /* immutable */,
                  false /* hidden */))
          .put(
              GRAVITINO_METALAKE,
              PropertyEntry.stringRequiredPropertyEntry(
                  GRAVITINO_METALAKE,
                  "The metalake name on the remote Gravitino server",
                  true /* immutable */,
                  false /* hidden */))
          .put(
              GRAVITINO_CATALOG,
              PropertyEntry.stringOptionalPropertyEntry(
                  GRAVITINO_CATALOG,
                  "The catalog name on the remote Gravitino server. Defaults to the local catalog"
                      + " name if not set.",
                  false /* immutable */,
                  null /* defaultValue */,
                  false /* hidden */))
          .put(
              GRAVITINO_AUTH_TYPE,
              PropertyEntry.stringOptionalPropertyEntry(
                  GRAVITINO_AUTH_TYPE,
                  "Authentication type for the remote Gravitino server: simple, basic",
                  false /* immutable */,
                  "simple" /* defaultValue */,
                  false /* hidden */))
          .put(
              GRAVITINO_AUTH_USERNAME,
              PropertyEntry.stringOptionalPropertyEntry(
                  GRAVITINO_AUTH_USERNAME,
                  "Username for simple authentication to the remote Gravitino server",
                  false /* immutable */,
                  null /* defaultValue */,
                  false /* hidden */))
          .put(
              GRAVITINO_AUTH_PASSWORD,
              PropertyEntry.stringOptionalPropertyEntry(
                  GRAVITINO_AUTH_PASSWORD,
                  "Password for basic authentication to the remote Gravitino server",
                  false /* immutable */,
                  null /* defaultValue */,
                  true /* hidden */))
          .put(
              GRAVITINO_CACHE_ENABLED,
              PropertyEntry.booleanPropertyEntry(
                  GRAVITINO_CACHE_ENABLED,
                  "Whether to enable local caching of remote metadata",
                  false /* required */,
                  false /* immutable */,
                  false /* defaultValue */,
                  false /* hidden */,
                  false /* reserved */))
          .put(
              GRAVITINO_CACHE_TTL,
              PropertyEntry.stringOptionalPropertyEntry(
                  GRAVITINO_CACHE_TTL,
                  "TTL for cached metadata entries (e.g., 60s, 5m, 1h)",
                  false /* immutable */,
                  "300s" /* defaultValue */,
                  false /* hidden */))
          .put(
              GRAVITINO_CACHE_MAX_SIZE,
              PropertyEntry.integerPropertyEntry(
                  GRAVITINO_CACHE_MAX_SIZE,
                  "Maximum number of entries in the metadata cache",
                  false /* required */,
                  false /* immutable */,
                  1000 /* defaultValue */,
                  false /* hidden */,
                  false /* reserved */))
          .build();

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return GRAVITINO_CATALOG_PROPERTY_ENTRIES;
  }
}
