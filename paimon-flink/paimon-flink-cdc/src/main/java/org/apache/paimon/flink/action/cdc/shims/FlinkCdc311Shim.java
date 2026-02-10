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
 * Shim implementation for Flink CDC 3.1.1.
 *
 * <p>This version does not support the additional configuration options introduced in Flink CDC
 * 3.5.0. The methods in this implementation are no-ops.
 */
public class FlinkCdc311Shim implements FlinkCdcShim {
    @Override
    public void configureMySqlSource(
            Configuration config, MySqlSourceBuilder<CdcSourceRecord> sourceBuilder) {}

    @Override
    public void configurePostgresSource(
            Configuration config, PostgresSourceBuilder<CdcSourceRecord> sourceBuilder) {}
}
