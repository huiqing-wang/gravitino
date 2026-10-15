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

import java.util.Arrays;
import java.util.Map;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.HasPropertyMetadata;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalog operations that forward metadata requests to a remote Gravitino instance via the
 * Gravitino Java REST Client. This enables federated metadata access across regions and clouds.
 *
 * <p>The server-side connector interface ({@link org.apache.gravitino.connector.SupportsSchemas})
 * uses {@link NameIdentifier}-based methods, while the client-side API ({@link SupportsSchemas})
 * uses String-based methods. This class bridges the two.
 */
public class GravitinoCatalogOperations
    implements CatalogOperations, org.apache.gravitino.connector.SupportsSchemas, TableCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoCatalogOperations.class);

  private GravitinoClient client;
  private String remoteCatalogName;
  private Catalog remoteCatalog;
  private SupportsSchemas remoteSchemas;
  private TableCatalog remoteTableCatalog;

  @Override
  public void initialize(
      Map<String, String> conf, CatalogInfo info, HasPropertyMetadata propertiesMetadata)
      throws RuntimeException {
    String uri = conf.get(GravitinoCatalogPropertiesMetadata.GRAVITINO_URI);
    String metalake = conf.get(GravitinoCatalogPropertiesMetadata.GRAVITINO_METALAKE);
    String authType =
        conf.getOrDefault(GravitinoCatalogPropertiesMetadata.GRAVITINO_AUTH_TYPE, "simple");
    String username = conf.get(GravitinoCatalogPropertiesMetadata.GRAVITINO_AUTH_USERNAME);
    String password = conf.get(GravitinoCatalogPropertiesMetadata.GRAVITINO_AUTH_PASSWORD);

    this.remoteCatalogName =
        conf.getOrDefault(GravitinoCatalogPropertiesMetadata.GRAVITINO_CATALOG, info.name());

    GravitinoClient.ClientBuilder builder = GravitinoClient.builder(uri).withMetalake(metalake);

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

    this.client = builder.build();
    this.remoteCatalog = client.loadCatalog(remoteCatalogName);
    this.remoteSchemas = remoteCatalog.asSchemas();
    this.remoteTableCatalog = remoteCatalog.asTableCatalog();

    LOG.info(
        "Initialized Gravitino federated catalog: uri={}, metalake={}, remoteCatalog={}",
        uri,
        metalake,
        remoteCatalogName);
  }

  @Override
  public void testConnection(
      NameIdentifier catalogIdent,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties) {
    try {
      client.listCatalogs();
    } catch (Exception e) {
      throw new ConnectionFailedException(
          e, "Failed to connect to remote Gravitino server: %s", e.getMessage());
    }
  }

  @Override
  public void close() {
    if (client != null) {
      try {
        client.close();
      } catch (Exception e) {
        LOG.warn("Failed to close Gravitino client", e);
      }
    }
  }

  // ==================== Schema Operations ====================
  // Server-side interface uses NameIdentifier; client-side API uses String.

  @Override
  public NameIdentifier[] listSchemas(Namespace namespace) throws NoSuchCatalogException {
    // Client-side listSchemas() returns String[] of schema names
    String[] schemaNames = remoteSchemas.listSchemas();
    return Arrays.stream(schemaNames)
        .map(name -> NameIdentifier.of(namespace, name))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public GravitinoSchema createSchema(
      NameIdentifier ident, String comment, Map<String, String> properties)
      throws NoSuchCatalogException, SchemaAlreadyExistsException {
    Schema created = remoteSchemas.createSchema(ident.name(), comment, properties);
    return toGravitinoSchema(created);
  }

  @Override
  public GravitinoSchema loadSchema(NameIdentifier ident) throws NoSuchSchemaException {
    Schema loaded = remoteSchemas.loadSchema(ident.name());
    return toGravitinoSchema(loaded);
  }

  @Override
  public GravitinoSchema alterSchema(NameIdentifier ident, SchemaChange... changes)
      throws NoSuchSchemaException {
    Schema altered = remoteSchemas.alterSchema(ident.name(), changes);
    return toGravitinoSchema(altered);
  }

  @Override
  public boolean dropSchema(NameIdentifier ident, boolean cascade) throws NonEmptySchemaException {
    return remoteSchemas.dropSchema(ident.name(), cascade);
  }

  // ==================== Table Operations ====================
  // Client-side TableCatalog uses NameIdentifier with namespace=[schema], ident=[schema, table].

  @Override
  public NameIdentifier[] listTables(Namespace namespace) throws NoSuchSchemaException {
    // Local namespace: [metalake, catalog, schema]
    // Remote expects: Namespace.of(schema)
    Namespace remoteNamespace = toRemoteSchemaNamespace(namespace);
    NameIdentifier[] remoteIdents = remoteTableCatalog.listTables(remoteNamespace);
    // Convert back to local namespace format
    return Arrays.stream(remoteIdents)
        .map(ident -> NameIdentifier.of(namespace, ident.name()))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public Table loadTable(NameIdentifier tableIdent) throws NoSuchTableException {
    NameIdentifier remoteIdent = toRemoteTableIdentifier(tableIdent);
    Table loaded = remoteTableCatalog.loadTable(remoteIdent);
    return toGravitinoTable(loaded);
  }

  @Override
  public Table createTable(
      NameIdentifier tableIdent,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes)
      throws NoSuchSchemaException, TableAlreadyExistsException {
    NameIdentifier remoteIdent = toRemoteTableIdentifier(tableIdent);
    Table created =
        remoteTableCatalog.createTable(
            remoteIdent,
            columns,
            comment,
            properties,
            partitioning,
            distribution,
            sortOrders,
            indexes);
    return toGravitinoTable(created);
  }

  @Override
  public Table alterTable(NameIdentifier tableIdent, TableChange... changes)
      throws NoSuchTableException, IllegalArgumentException {
    NameIdentifier remoteIdent = toRemoteTableIdentifier(tableIdent);
    Table altered = remoteTableCatalog.alterTable(remoteIdent, changes);
    return toGravitinoTable(altered);
  }

  @Override
  public boolean dropTable(NameIdentifier tableIdent) {
    NameIdentifier remoteIdent = toRemoteTableIdentifier(tableIdent);
    return remoteTableCatalog.dropTable(remoteIdent);
  }

  @Override
  public boolean purgeTable(NameIdentifier tableIdent) throws UnsupportedOperationException {
    NameIdentifier remoteIdent = toRemoteTableIdentifier(tableIdent);
    return remoteTableCatalog.purgeTable(remoteIdent);
  }

  // ==================== Namespace Conversion ====================

  /**
   * Converts local namespace [metalake, catalog, schema...] to remote namespace [schema...]. The
   * client-side TableCatalog expects namespace with just the schema level(s).
   */
  private Namespace toRemoteSchemaNamespace(Namespace namespace) {
    // Local: [metalake, catalog, schema] -> Remote: [schema]
    if (namespace.length() <= 2) {
      return Namespace.empty();
    }
    String[] schemaLevels = Arrays.copyOfRange(namespace.levels(), 2, namespace.length());
    return Namespace.of(schemaLevels);
  }

  /**
   * Converts local table identifier [metalake, catalog, schema, table] to remote [schema, table].
   */
  private NameIdentifier toRemoteTableIdentifier(NameIdentifier tableIdent) {
    Namespace remoteNamespace = toRemoteSchemaNamespace(tableIdent.namespace());
    return NameIdentifier.of(remoteNamespace, tableIdent.name());
  }

  // ==================== Entity Conversion ====================

  private GravitinoSchema toGravitinoSchema(Schema schema) {
    return GravitinoSchema.builder()
        .withName(schema.name())
        .withComment(schema.comment())
        .withProperties(schema.properties())
        .withAuditInfo(toAuditInfo(schema.auditInfo()))
        .build();
  }

  private GravitinoTable toGravitinoTable(Table table) {
    GravitinoColumn[] columns =
        Arrays.stream(table.columns())
            .map(
                col ->
                    GravitinoColumn.builder()
                        .withName(col.name())
                        .withType(col.dataType())
                        .withComment(col.comment())
                        .withNullable(col.nullable())
                        .withAutoIncrement(col.autoIncrement())
                        .withDefaultValue(col.defaultValue())
                        .build())
            .toArray(GravitinoColumn[]::new);

    return GravitinoTable.builder()
        .withName(table.name())
        .withColumns(columns)
        .withComment(table.comment())
        .withProperties(table.properties())
        .withAuditInfo(toAuditInfo(table.auditInfo()))
        .withPartitioning(table.partitioning())
        .withDistribution(table.distribution() != null ? table.distribution() : Distributions.NONE)
        .withSortOrders(table.sortOrder())
        .withIndexes(table.index())
        .build();
  }

  /**
   * Converts an API-level {@link Audit} (as returned by the remote client, typically an {@code
   * AuditDTO}) into the core {@link AuditInfo} required by the entity builders.
   */
  private AuditInfo toAuditInfo(Audit audit) {
    if (audit == null) {
      return AuditInfo.EMPTY;
    }
    if (audit instanceof AuditInfo) {
      return (AuditInfo) audit;
    }
    return AuditInfo.builder()
        .withCreator(audit.creator())
        .withCreateTime(audit.createTime())
        .withLastModifier(audit.lastModifier())
        .withLastModifiedTime(audit.lastModifiedTime())
        .build();
  }
}
