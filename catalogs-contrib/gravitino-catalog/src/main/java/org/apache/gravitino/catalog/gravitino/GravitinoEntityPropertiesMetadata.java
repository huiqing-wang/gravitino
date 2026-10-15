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

import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.connector.BasePropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * A permissive properties metadata for federated schema and table entities.
 *
 * <p>A federated proxy catalog simply forwards schema/table properties to and from the remote
 * Gravitino instance, so it neither requires nor validates any specific property. Declaring no
 * specific entries lets arbitrary remote properties pass through untouched while still satisfying
 * the server's property-metadata lookups (e.g. resolving hidden property names).
 */
public class GravitinoEntityPropertiesMetadata extends BasePropertiesMetadata {

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return Collections.emptyMap();
  }
}
