/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.shims;

import org.apache.paimon.flink.action.cdc.CdcSourceRecord;

import org.apache.flink.cdc.connectors.mysql.source.MySqlSourceBuilder;
import org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceOptions;
import org.apache.flink.cdc.connectors.postgres.source.PostgresSourceBuilder;
import org.apache.flink.cdc.connectors.postgres.source.config.PostgresSourceOptions;
import org.apache.flink.configuration.Configuration;

/**
 * Shim implementation for Flink CDC 3.5.0.
 *
 * <p>This version supports additional configuration options:
 *
 * <ul>
 *   <li>SCAN_INCREMENTAL_SNAPSHOT_UNBOUNDED_CHUNK_FIRST for MySQL
 *   <li>PARSE_ONLINE_SCHEMA_CHANGES for MySQL
 *   <li>USE_LEGACY_JSON_FORMAT for MySQL
 *   <li>SCAN_INCREMENTAL_SNAPSHOT_UNBOUNDED_CHUNK_FIRST_ENABLED for PostgreSQL
 * </ul>
 */
public class FlinkCdc350Shim implements FlinkCdcShim {
    @Override
    public void configureMySqlSource(
            Configuration config, MySqlSourceBuilder<CdcSourceRecord> sourceBuilder) {
        config.getOptional(MySqlSourceOptions.SCAN_INCREMENTAL_SNAPSHOT_UNBOUNDED_CHUNK_FIRST)
                .ifPresent(sourceBuilder::assignUnboundedChunkFirst);
        config.getOptional(MySqlSourceOptions.PARSE_ONLINE_SCHEMA_CHANGES)
                .ifPresent(sourceBuilder::parseOnLineSchemaChanges);
        config.getOptional(MySqlSourceOptions.USE_LEGACY_JSON_FORMAT)
                .ifPresent(sourceBuilder::useLegacyJsonFormat);
    }

    @Override
    public void configurePostgresSource(
            Configuration config, PostgresSourceBuilder<CdcSourceRecord> sourceBuilder) {
        config.getOptional(
                        PostgresSourceOptions
                                .SCAN_INCREMENTAL_SNAPSHOT_UNBOUNDED_CHUNK_FIRST_ENABLED)
                .ifPresent(sourceBuilder::assignUnboundedChunkFirst);
    }
}
