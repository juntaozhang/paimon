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
import org.apache.flink.cdc.connectors.postgres.source.PostgresSourceBuilder;
import org.apache.flink.configuration.Configuration;

/**
 * A shim interface to abstract different Flink CDC versions.
 *
 * <p>This interface provides methods to configure CDC source builders with version-specific
 * options. Different Flink CDC versions support different configuration options, this shim pattern
 * allows Paimon to work with multiple CDC versions.
 *
 * @see <a
 *     href="https://nightlies.apache.org/flink/flink-cdc-docs-master/docs/connectors/flink-sources/overview/#supported-flink-versions">Flink
 *     CDC sources Supported Flink Versions</a>
 */
public interface FlinkCdcShim {
    /**
     * Configures MySQL source builder with version-specific options.
     *
     * @param config the configuration containing CDC options
     * @param sourceBuilder the MySQL source builder to configure
     */
    void configureMySqlSource(
            Configuration config, MySqlSourceBuilder<CdcSourceRecord> sourceBuilder);

    /**
     * Configures PostgreSQL source builder with version-specific options.
     *
     * @param config the configuration containing CDC options
     * @param sourceBuilder the PostgreSQL source builder to configure
     */
    void configurePostgresSource(
            Configuration config, PostgresSourceBuilder<CdcSourceRecord> sourceBuilder);
}
