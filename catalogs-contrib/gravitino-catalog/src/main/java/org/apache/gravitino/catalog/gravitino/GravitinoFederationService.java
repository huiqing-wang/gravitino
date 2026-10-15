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

import java.io.Closeable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.client.GravitinoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that synchronizes catalogs from a remote Gravitino instance into a local metalake as
 * federated proxy catalogs (provider="gravitino").
 *
 * <p>Usage flow:
 *
 * <ol>
 *   <li>Create a local metalake with federation properties (federation.type, federation.uri, etc.)
 *   <li>Call {@link #syncCatalogs()} to automatically create proxy catalogs for each remote catalog
 * </ol>
 *
 * <p>This achieves metalake-level federation without modifying Gravitino core:
 *
 * <pre>
 * Local metalake (region_b_federation)
 *   ├─ catalog_1 (provider=gravitino) → Remote metalake.region_b_ml.catalog_1
 *   ├─ catalog_2 (provider=gravitino) → Remote metalake.region_b_ml.catalog_2
 *   └─ ...
 * </pre>
 */
public class GravitinoFederationService implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoFederationService.class);

  /** Property key prefix for federation configuration on the local metalake. */
  public static final String FEDERATION_PREFIX = "federation.";

  public static final String FEDERATION_TYPE = FEDERATION_PREFIX + "type";
  public static final String FEDERATION_URI = FEDERATION_PREFIX + "uri";
  public static final String FEDERATION_METALAKE = FEDERATION_PREFIX + "metalake";
  public static final String FEDERATION_AUTH_TYPE = FEDERATION_PREFIX + "auth.type";
  public static final String FEDERATION_AUTH_USERNAME = FEDERATION_PREFIX + "auth.username";
  public static final String FEDERATION_AUTH_PASSWORD = FEDERATION_PREFIX + "auth.password";

  /** The provider name for federated catalogs. */
  public static final String FEDERATED_CATALOG_PROVIDER = "gravitino";

  private final String localUri;
  private final String localMetalakeName;
  private final String remoteUri;
  private final String remoteMetalakeName;
  private final String authType;
  private final String username;
  private final String password;

  private GravitinoClient localClient;
  private GravitinoClient remoteClient;

  /**
   * Creates a federation service instance.
   *
   * @param localUri URI of the local Gravitino server (e.g., http://localhost:8090)
   * @param localMetalakeName Name of the local metalake that holds the federated catalogs
   * @param federationProperties The federation properties stored on the local metalake
   */
  public GravitinoFederationService(
      String localUri, String localMetalakeName, Map<String, String> federationProperties) {
    this.localUri = localUri;
    this.localMetalakeName = localMetalakeName;
    this.remoteUri = federationProperties.get(FEDERATION_URI);
    this.remoteMetalakeName = federationProperties.get(FEDERATION_METALAKE);
    this.authType = federationProperties.getOrDefault(FEDERATION_AUTH_TYPE, "simple");
    this.username = federationProperties.get(FEDERATION_AUTH_USERNAME);
    this.password = federationProperties.get(FEDERATION_AUTH_PASSWORD);
  }

  /**
   * Checks whether the given metalake properties indicate a federated metalake.
   *
   * @param properties The metalake properties.
   * @return true if federation.type is set to "gravitino".
   */
  public static boolean isFederatedMetalake(Map<String, String> properties) {
    if (properties == null) {
      return false;
    }
    return "gravitino".equalsIgnoreCase(properties.get(FEDERATION_TYPE));
  }

  /**
   * Extracts federation-prefixed properties from metalake properties.
   *
   * @param properties The full metalake properties map.
   * @return A map containing only the federation.* properties.
   */
  public static Map<String, String> extractFederationProperties(Map<String, String> properties) {
    Map<String, String> federationProps = new HashMap<>();
    if (properties != null) {
      properties.forEach(
          (key, value) -> {
            if (key.startsWith(FEDERATION_PREFIX)) {
              federationProps.put(key, value);
            }
          });
    }
    return federationProps;
  }

  /**
   * Synchronizes all catalogs from the remote Gravitino metalake into the local metalake as
   * federated proxy catalogs. For each remote catalog:
   *
   * <ul>
   *   <li>If a local catalog with the same name already exists, skip it
   *   <li>Otherwise, create a local catalog with provider="gravitino" pointing to the remote
   * </ul>
   *
   * @return The number of new proxy catalogs created.
   */
  public int syncCatalogs() {
    LOG.info(
        "Starting federation sync: local={}({}), remote={}({})",
        localMetalakeName,
        localUri,
        remoteMetalakeName,
        remoteUri);

    GravitinoClient remote = getRemoteClient();
    GravitinoClient local = getLocalClient();

    // List all catalogs on the remote metalake
    Catalog[] remoteCatalogs = remote.listCatalogsInfo();
    LOG.info(
        "Found {} catalogs on remote metalake '{}'", remoteCatalogs.length, remoteMetalakeName);

    // List existing local catalogs to avoid duplicates
    String[] localCatalogNames = local.listCatalogs();
    int created = 0;

    for (Catalog remoteCatalog : remoteCatalogs) {
      String catalogName = remoteCatalog.name();

      if (Arrays.asList(localCatalogNames).contains(catalogName)) {
        LOG.info("Local catalog '{}' already exists, skipping", catalogName);
        continue;
      }

      // Build properties for the federated proxy catalog
      Map<String, String> proxyProperties = buildProxyCatalogProperties(catalogName);

      try {
        local.createCatalog(
            catalogName,
            remoteCatalog.type(),
            FEDERATED_CATALOG_PROVIDER,
            "Federated proxy to " + remoteMetalakeName + "." + catalogName,
            proxyProperties);
        created++;
        LOG.info(
            "Created federated proxy catalog '{}' (type={}) -> remote {}.{}",
            catalogName,
            remoteCatalog.type(),
            remoteMetalakeName,
            catalogName);
      } catch (Exception e) {
        LOG.error("Failed to create proxy catalog '{}': {}", catalogName, e.getMessage(), e);
      }
    }

    LOG.info("Federation sync complete: {} new catalogs created", created);
    return created;
  }

  /**
   * Removes all federated proxy catalogs from the local metalake that point to the remote instance.
   *
   * @param force Whether to force-drop catalogs with existing entities.
   * @return The number of catalogs dropped.
   */
  public int unsyncCatalogs(boolean force) {
    GravitinoClient local = getLocalClient();
    GravitinoClient remote = getRemoteClient();

    Catalog[] remoteCatalogs = remote.listCatalogsInfo();
    int dropped = 0;

    for (Catalog remoteCatalog : remoteCatalogs) {
      String catalogName = remoteCatalog.name();
      try {
        Catalog localCatalog = local.loadCatalog(catalogName);
        // Only drop if it's a federated proxy catalog
        if (FEDERATED_CATALOG_PROVIDER.equals(localCatalog.provider())) {
          local.dropCatalog(catalogName, force);
          dropped++;
          LOG.info("Dropped federated proxy catalog '{}'", catalogName);
        }
      } catch (Exception e) {
        LOG.warn("Failed to drop proxy catalog '{}': {}", catalogName, e.getMessage());
      }
    }

    return dropped;
  }

  /**
   * Builds the properties map for a federated proxy catalog pointing to a specific remote catalog.
   */
  private Map<String, String> buildProxyCatalogProperties(String remoteCatalogName) {
    Map<String, String> props = new HashMap<>();
    props.put(GravitinoCatalogPropertiesMetadata.GRAVITINO_URI, remoteUri);
    props.put(GravitinoCatalogPropertiesMetadata.GRAVITINO_METALAKE, remoteMetalakeName);
    props.put(GravitinoCatalogPropertiesMetadata.GRAVITINO_CATALOG, remoteCatalogName);
    props.put(GravitinoCatalogPropertiesMetadata.GRAVITINO_AUTH_TYPE, authType);

    if (username != null) {
      props.put(GravitinoCatalogPropertiesMetadata.GRAVITINO_AUTH_USERNAME, username);
    }
    if (password != null) {
      props.put(GravitinoCatalogPropertiesMetadata.GRAVITINO_AUTH_PASSWORD, password);
    }

    return props;
  }

  private GravitinoClient getRemoteClient() {
    if (remoteClient == null) {
      GravitinoClient.ClientBuilder builder =
          GravitinoClient.builder(remoteUri).withMetalake(remoteMetalakeName);
      applyAuth(builder);
      remoteClient = builder.build();
    }
    return remoteClient;
  }

  private GravitinoClient getLocalClient() {
    if (localClient == null) {
      localClient =
          GravitinoClient.builder(localUri)
              .withMetalake(localMetalakeName)
              .withSimpleAuth()
              .build();
    }
    return localClient;
  }

  private void applyAuth(GravitinoClient.ClientBuilder builder) {
    switch (authType.toLowerCase()) {
      case "basic":
        builder.withBasicAuth(username, password);
        break;
      case "simple":
      default:
        if (username != null && !username.isEmpty()) {
          builder.withSimpleAuth(username);
        } else {
          builder.withSimpleAuth();
        }
        break;
    }
  }

  @Override
  public void close() {
    if (remoteClient != null) {
      try {
        remoteClient.close();
      } catch (Exception e) {
        LOG.warn("Failed to close remote client", e);
      }
    }
    if (localClient != null) {
      try {
        localClient.close();
      } catch (Exception e) {
        LOG.warn("Failed to close local client", e);
      }
    }
  }
}
