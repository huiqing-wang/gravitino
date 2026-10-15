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

/**
 * Constants for metalake-level Gravitino-to-Gravitino federation.
 *
 * <p>A metalake carrying {@link #FEDERATION_TYPE}={@code gravitino} is a federated metalake: its
 * catalog list is mirrored live from a remote Gravitino metalake. The {@code federation.*} keys are
 * read from the local metalake properties; the {@code gravitino.*} keys are written onto each
 * mirrored proxy catalog so the {@code gravitino} connector can forward schema/table operations to
 * the remote instance.
 */
public final class FederationConstants {

  /** Prefix of the federation configuration stored on the local metalake. */
  public static final String FEDERATION_PREFIX = "federation.";

  /**
   * Metalake property that marks it as federated; expected value {@link
   * #FEDERATION_TYPE_GRAVITINO}.
   */
  public static final String FEDERATION_TYPE = FEDERATION_PREFIX + "type";

  /** Value of {@link #FEDERATION_TYPE} that enables Gravitino-to-Gravitino federation. */
  public static final String FEDERATION_TYPE_GRAVITINO = "gravitino";

  /** Remote Gravitino server URI. */
  public static final String FEDERATION_URI = FEDERATION_PREFIX + "uri";

  /** Remote metalake name to mirror. */
  public static final String FEDERATION_METALAKE = FEDERATION_PREFIX + "metalake";

  /** Remote authentication type: {@code simple} or {@code basic}. */
  public static final String FEDERATION_AUTH_TYPE = FEDERATION_PREFIX + "auth.type";

  /** Remote authentication username. */
  public static final String FEDERATION_AUTH_USERNAME = FEDERATION_PREFIX + "auth.username";

  /** Remote authentication password. */
  public static final String FEDERATION_AUTH_PASSWORD = FEDERATION_PREFIX + "auth.password";

  /**
   * Minimum interval, in seconds, between two live mirror refreshes for the same metalake. Used to
   * avoid hitting the remote instance on every list request.
   */
  public static final String FEDERATION_REFRESH_INTERVAL = FEDERATION_PREFIX + "refresh.interval";

  /** Default value of {@link #FEDERATION_REFRESH_INTERVAL} in seconds. */
  public static final long DEFAULT_REFRESH_INTERVAL_SECONDS = 30L;

  /** The catalog provider used for mirrored proxy catalogs. */
  public static final String FEDERATED_CATALOG_PROVIDER = "gravitino";

  // Proxy catalog property keys, consumed by the gravitino connector.

  /** Proxy catalog property: remote Gravitino server URI. */
  public static final String GRAVITINO_URI = "gravitino.uri";

  /** Proxy catalog property: remote metalake name. */
  public static final String GRAVITINO_METALAKE = "gravitino.metalake";

  /** Proxy catalog property: remote catalog name. */
  public static final String GRAVITINO_CATALOG = "gravitino.catalog";

  /** Proxy catalog property: remote authentication type. */
  public static final String GRAVITINO_AUTH_TYPE = "gravitino.auth.type";

  /** Proxy catalog property: remote authentication username. */
  public static final String GRAVITINO_AUTH_USERNAME = "gravitino.auth.username";

  /** Proxy catalog property: remote authentication password. */
  public static final String GRAVITINO_AUTH_PASSWORD = "gravitino.auth.password";

  private FederationConstants() {}
}
