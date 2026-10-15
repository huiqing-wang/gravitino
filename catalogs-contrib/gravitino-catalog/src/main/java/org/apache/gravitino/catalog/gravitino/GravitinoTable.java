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

import com.google.common.collect.Maps;
import lombok.ToString;
import org.apache.gravitino.connector.BaseTable;
import org.apache.gravitino.connector.TableOperations;

/** Represents a table entity from a remote Gravitino instance. */
@ToString
public class GravitinoTable extends BaseTable {

  private GravitinoTable() {}

  @Override
  protected TableOperations newOps() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("GravitinoTable does not support TableOperations.");
  }

  /** A builder class for constructing GravitinoTable instances. */
  public static class Builder extends BaseTableBuilder<Builder, GravitinoTable> {

    private Builder() {}

    @Override
    protected GravitinoTable internalBuild() {
      GravitinoTable table = new GravitinoTable();
      table.name = name;
      table.comment = comment;
      table.properties = properties != null ? Maps.newHashMap(properties) : Maps.newHashMap();
      table.auditInfo = auditInfo;
      table.columns = columns;
      table.partitioning = partitioning;
      table.distribution = distribution;
      table.sortOrders = sortOrders;
      table.indexes = indexes;
      return table;
    }
  }

  /**
   * Creates a new instance of {@link Builder}.
   *
   * @return The new instance.
   */
  public static Builder builder() {
    return new Builder();
  }
}
