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
package org.apache.gravitino.server.web.federation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CatalogDispatcher} decorator that provides transparent, metalake-level federation with a
 * remote Gravitino instance.
 *
 * <p>A metalake whose properties contain {@code federation.type=gravitino} is a <em>federated</em>
 * metalake. For such a metalake this decorator keeps its catalog list mirrored from the remote
 * metalake:
 *
 * <ul>
 *   <li>Read operations ({@code listCatalogs}, {@code listCatalogsInfo}, {@code loadCatalog}) first
 *       lazily mirror the remote catalogs into local proxy catalogs ({@code provider=gravitino}),
 *       then delegate to the underlying dispatcher. Because the proxies are real local catalog
 *       entities, all sub-entities (schemas, tables, ...) resolve through the standard dispatchers
 *       and the {@code gravitino} connector, with no core changes.
 *   <li>Write operations issued directly against a federated metalake are rejected: the federated
 *       metalake is an independent, read-only view, and mutations must be performed on the remote
 *       metalake.
 * </ul>
 *
 * <p>Mirroring is rate-limited per metalake by {@code federation.refresh.interval} (seconds) so
 * that a remote round-trip is not performed on every list request. Loading a catalog that is not
 * present locally forces an immediate refresh.
 */
public class FederatedCatalogDispatcher implements CatalogDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(FederatedCatalogDispatcher.class);

  private final CatalogDispatcher delegate;
  private final MetalakeDispatcher metalakeDispatcher;

  /** Last successful mirror timestamp (epoch seconds) per metalake name. */
  private final Map<String, Long> lastMirrorAt = new ConcurrentHashMap<>();

  /** Per-metalake monitor to serialize concurrent mirror operations. */
  private final Map<String, Object> mirrorLocks = new ConcurrentHashMap<>();

  /**
   * Creates the decorator.
   *
   * @param delegate The underlying catalog dispatcher that talks to the local store.
   * @param metalakeDispatcher Used to read the local metalake properties (federation config).
   */
  public FederatedCatalogDispatcher(
      CatalogDispatcher delegate, MetalakeDispatcher metalakeDispatcher) {
    this.delegate = delegate;
    this.metalakeDispatcher = metalakeDispatcher;
  }

  // ==================== Read operations (federation-aware) ====================

  @Override
  public NameIdentifier[] listCatalogs(Namespace namespace) {
    mirrorIfFederated(namespace, false);
    return delegate.listCatalogs(namespace);
  }

  @Override
  public Catalog[] listCatalogsInfo(Namespace namespace) {
    mirrorIfFederated(namespace, false);
    return delegate.listCatalogsInfo(namespace);
  }

  @Override
  public Catalog loadCatalog(NameIdentifier ident) {
    // Force a refresh when the catalog is unknown locally so newly added remote
    // catalogs resolve on demand.
    if (federationContext(metalakeOf(ident)) != null && !delegate.catalogExists(ident)) {
      mirrorIfFederated(ident.namespace(), true);
    }
    return delegate.loadCatalog(ident);
  }

  // ==================== Write operations (rejected when federated) ====================

  @Override
  public Catalog createCatalog(
      NameIdentifier ident,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties) {
    rejectIfFederated(ident);
    return delegate.createCatalog(ident, type, provider, comment, properties);
  }

  @Override
  public Catalog alterCatalog(NameIdentifier ident, CatalogChange... changes) {
    rejectIfFederated(ident);
    return delegate.alterCatalog(ident, changes);
  }

  @Override
  public boolean dropCatalog(NameIdentifier ident, boolean force) {
    rejectIfFederated(ident);
    return delegate.dropCatalog(ident, force);
  }

  @Override
  public void testConnection(
      NameIdentifier ident,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties)
      throws Exception {
    rejectIfFederated(ident);
    delegate.testConnection(ident, type, provider, comment, properties);
  }

  @Override
  public void enableCatalog(NameIdentifier ident) {
    rejectIfFederated(ident);
    delegate.enableCatalog(ident);
  }

  @Override
  public void disableCatalog(NameIdentifier ident) {
    rejectIfFederated(ident);
    delegate.disableCatalog(ident);
  }

  // ==================== Mirroring ====================

  private void mirrorIfFederated(Namespace namespace, boolean force) {
    NameIdentifier metalakeIdent = NameIdentifier.of(namespace.levels());
    FederationContext ctx = federationContext(metalakeIdent);
    if (ctx == null) {
      return;
    }

    String key = metalakeIdent.name();
    long now = System.currentTimeMillis() / 1000L;
    if (!force) {
      Long last = lastMirrorAt.get(key);
      if (last != null && now - last < ctx.refreshIntervalSeconds) {
        return;
      }
    }

    Object lock = mirrorLocks.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      long lockedNow = System.currentTimeMillis() / 1000L;
      if (!force) {
        Long last = lastMirrorAt.get(key);
        if (last != null && lockedNow - last < ctx.refreshIntervalSeconds) {
          return;
        }
      }
      try {
        doMirror(namespace, ctx);
        lastMirrorAt.put(key, System.currentTimeMillis() / 1000L);
      } catch (Exception e) {
        LOG.warn(
            "Federation mirror failed for metalake '{}' (remote {}): {}",
            key,
            ctx.remoteUri,
            e.getMessage());
      }
    }
  }

  private void doMirror(Namespace namespace, FederationContext ctx) {
    try (GravitinoClient remote = ctx.newRemoteClient()) {
      Catalog[] remoteCatalogs = remote.listCatalogsInfo();
      Set<String> remoteNames =
          Arrays.stream(remoteCatalogs).map(Catalog::name).collect(Collectors.toSet());

      Catalog[] localCatalogs = delegate.listCatalogsInfo(namespace);
      Set<String> localNames =
          Arrays.stream(localCatalogs).map(Catalog::name).collect(Collectors.toSet());

      // Create proxy catalogs for remote catalogs missing locally.
      for (Catalog rc : remoteCatalogs) {
        if (localNames.contains(rc.name())) {
          continue;
        }
        try {
          delegate.createCatalog(
              NameIdentifier.of(namespace, rc.name()),
              rc.type(),
              FederationConstants.FEDERATED_CATALOG_PROVIDER,
              "Federated proxy to " + ctx.remoteMetalake + "." + rc.name(),
              ctx.buildProxyProperties(rc.name()));
          LOG.info(
              "Federation: created proxy catalog '{}' in metalake '{}' (remote {}.{})",
              rc.name(),
              ctx.localMetalakeName,
              ctx.remoteMetalake,
              rc.name());
        } catch (Exception e) {
          LOG.warn(
              "Federation: failed to create proxy catalog '{}': {}", rc.name(), e.getMessage());
        }
      }

      // Drop local proxy catalogs that no longer exist remotely.
      for (Catalog lc : localCatalogs) {
        if (!FederationConstants.FEDERATED_CATALOG_PROVIDER.equals(lc.provider())
            || remoteNames.contains(lc.name())) {
          continue;
        }
        try {
          delegate.dropCatalog(NameIdentifier.of(namespace, lc.name()), true);
          LOG.info(
              "Federation: dropped vanished proxy catalog '{}' in metalake '{}'",
              lc.name(),
              ctx.localMetalakeName);
        } catch (Exception e) {
          LOG.warn("Federation: failed to drop proxy catalog '{}': {}", lc.name(), e.getMessage());
        }
      }
    }
  }

  // ==================== Helpers ====================

  private void rejectIfFederated(NameIdentifier ident) {
    FederationContext ctx = federationContext(metalakeOf(ident));
    if (ctx != null) {
      throw new IllegalArgumentException(
          "Metalake '"
              + ctx.localMetalakeName
              + "' is a federated (read-only) view of remote metalake '"
              + ctx.remoteMetalake
              + "'. Perform catalog write operations directly on the remote Gravitino instance.");
    }
  }

  private NameIdentifier metalakeOf(NameIdentifier ident) {
    return NameIdentifier.of(ident.namespace().levels());
  }

  /**
   * Loads the metalake and returns its federation context, or {@code null} if the metalake does not
   * exist or is not federated.
   */
  private FederationContext federationContext(NameIdentifier metalakeIdent) {
    try {
      Metalake metalake = metalakeDispatcher.loadMetalake(metalakeIdent);
      Map<String, String> props = metalake.properties();
      if (props == null
          || !FederationConstants.FEDERATION_TYPE_GRAVITINO.equalsIgnoreCase(
              props.get(FederationConstants.FEDERATION_TYPE))) {
        return null;
      }
      return new FederationContext(metalakeIdent.name(), props);
    } catch (Exception e) {
      return null;
    }
  }

  /** Immutable per-metalake federation configuration derived from the metalake properties. */
  private static final class FederationContext {

    private final String localMetalakeName;
    private final String remoteUri;
    private final String remoteMetalake;
    private final String authType;
    private final String username;
    private final String password;
    private final long refreshIntervalSeconds;

    private FederationContext(String localMetalakeName, Map<String, String> props) {
      this.localMetalakeName = localMetalakeName;
      this.remoteUri = props.get(FederationConstants.FEDERATION_URI);
      this.remoteMetalake = props.get(FederationConstants.FEDERATION_METALAKE);
      this.authType = props.getOrDefault(FederationConstants.FEDERATION_AUTH_TYPE, "simple");
      this.username = props.get(FederationConstants.FEDERATION_AUTH_USERNAME);
      this.password = props.get(FederationConstants.FEDERATION_AUTH_PASSWORD);
      this.refreshIntervalSeconds = parseRefreshInterval(props);
    }

    private static long parseRefreshInterval(Map<String, String> props) {
      String raw = props.get(FederationConstants.FEDERATION_REFRESH_INTERVAL);
      if (raw == null || raw.isEmpty()) {
        return FederationConstants.DEFAULT_REFRESH_INTERVAL_SECONDS;
      }
      try {
        long value = Long.parseLong(raw.trim());
        return value >= 0 ? value : FederationConstants.DEFAULT_REFRESH_INTERVAL_SECONDS;
      } catch (NumberFormatException e) {
        return FederationConstants.DEFAULT_REFRESH_INTERVAL_SECONDS;
      }
    }

    private GravitinoClient newRemoteClient() {
      GravitinoClient.ClientBuilder builder =
          GravitinoClient.builder(remoteUri).withMetalake(remoteMetalake);
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
      return builder.build();
    }

    private Map<String, String> buildProxyProperties(String remoteCatalogName) {
      Map<String, String> props = new HashMap<>();
      props.put(FederationConstants.GRAVITINO_URI, remoteUri);
      props.put(FederationConstants.GRAVITINO_METALAKE, remoteMetalake);
      props.put(FederationConstants.GRAVITINO_CATALOG, remoteCatalogName);
      props.put(FederationConstants.GRAVITINO_AUTH_TYPE, authType);
      if (username != null && !username.isEmpty()) {
        props.put(FederationConstants.GRAVITINO_AUTH_USERNAME, username);
      }
      if (password != null && !password.isEmpty()) {
        props.put(FederationConstants.GRAVITINO_AUTH_PASSWORD, password);
      }
      return props;
    }
  }
}
