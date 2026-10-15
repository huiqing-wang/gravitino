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

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line tool that triggers Gravitino-to-Gravitino federation at the metalake level.
 *
 * <p>It reads the {@code federation.*} properties from a local metalake and drives {@link
 * GravitinoFederationService} to create or remove federated proxy catalogs. This keeps federation
 * entirely client-side, so it requires no changes to the Gravitino server.
 *
 * <pre>
 * # Sync remote catalogs into the local metalake as proxy catalogs
 * java -cp ... FederationSyncCli sync --metalake region_b_federation \
 *      --local-uri http://localhost:8090
 *
 * # Remove the federated proxy catalogs
 * java -cp ... FederationSyncCli unsync --metalake region_b_federation --force
 * </pre>
 */
public class FederationSyncCli {

  private static final Logger LOG = LoggerFactory.getLogger(FederationSyncCli.class);

  private static final String ACTION_SYNC = "sync";
  private static final String ACTION_UNSYNC = "unsync";
  private static final String DEFAULT_LOCAL_URI = "http://localhost:8090";

  private FederationSyncCli() {}

  /**
   * Entry point for the federation sync CLI.
   *
   * @param args Command-line arguments. See the class Javadoc for usage.
   */
  public static void main(String[] args) {
    if (args.length < 1) {
      printUsage();
      System.exit(2);
      return;
    }

    String action = args[0];
    Map<String, String> options = parseOptions(args);

    String metalakeName = options.get("metalake");
    if (metalakeName == null || metalakeName.isEmpty()) {
      System.err.println("Error: --metalake is required");
      printUsage();
      System.exit(2);
      return;
    }

    String localUri = options.getOrDefault("local-uri", DEFAULT_LOCAL_URI);

    try {
      switch (action) {
        case ACTION_SYNC:
          doSync(localUri, metalakeName);
          break;
        case ACTION_UNSYNC:
          doUnsync(localUri, metalakeName, Boolean.parseBoolean(options.get("force")));
          break;
        default:
          System.err.println("Error: unknown action '" + action + "'");
          printUsage();
          System.exit(2);
      }
    } catch (Exception e) {
      LOG.error("Federation {} failed for metalake '{}'", action, metalakeName, e);
      System.err.println("Federation " + action + " failed: " + e.getMessage());
      System.exit(1);
    }
  }

  private static void doSync(String localUri, String metalakeName) {
    Map<String, String> metalakeProperties = loadMetalakeProperties(localUri, metalakeName);

    if (!GravitinoFederationService.isFederatedMetalake(metalakeProperties)) {
      throw new IllegalArgumentException(
          "Metalake '"
              + metalakeName
              + "' is not a federated metalake. "
              + "Set 'federation.type=gravitino' in the metalake properties.");
    }

    Map<String, String> federationProps =
        GravitinoFederationService.extractFederationProperties(metalakeProperties);

    try (GravitinoFederationService service =
        new GravitinoFederationService(localUri, metalakeName, federationProps)) {
      int synced = service.syncCatalogs();
      System.out.println("Federation sync completed: " + synced + " catalog(s) created.");
    }
  }

  private static void doUnsync(String localUri, String metalakeName, boolean force) {
    Map<String, String> metalakeProperties = loadMetalakeProperties(localUri, metalakeName);
    Map<String, String> federationProps =
        GravitinoFederationService.extractFederationProperties(metalakeProperties);

    try (GravitinoFederationService service =
        new GravitinoFederationService(localUri, metalakeName, federationProps)) {
      int dropped = service.unsyncCatalogs(force);
      System.out.println("Federation unsync completed: " + dropped + " catalog(s) removed.");
    }
  }

  private static Map<String, String> loadMetalakeProperties(String localUri, String metalakeName) {
    try (GravitinoAdminClient adminClient =
        GravitinoAdminClient.builder(localUri).withSimpleAuth().build()) {
      Metalake metalake = adminClient.loadMetalake(metalakeName);
      return metalake.properties() != null ? metalake.properties() : new HashMap<>();
    }
  }

  /**
   * Parses {@code --key value} and bare {@code --flag} arguments into a map, skipping the leading
   * action token.
   */
  private static Map<String, String> parseOptions(String[] args) {
    Map<String, String> options = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        continue;
      }
      String key = arg.substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        options.put(key, args[++i]);
      } else {
        options.put(key, "true");
      }
    }
    return options;
  }

  private static void printUsage() {
    System.err.println(
        "Usage: FederationSyncCli <sync|unsync> --metalake <name> "
            + "[--local-uri <uri>] [--force]");
  }
}
