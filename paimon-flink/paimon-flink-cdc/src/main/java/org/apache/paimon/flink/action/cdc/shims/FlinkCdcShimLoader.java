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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Loader for Flink CDC shim implementations.
 *
 * <p>This class loads the appropriate {@link FlinkCdcShim} implementation based on the available
 * Flink CDC version. The shim implementation is specified in
 * META-INF/services/org.apache.paimon.flink.action.cdc.shims.FlinkCdcShim.
 */
public class FlinkCdcShimLoader {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkCdcShimLoader.class);
    private static final FlinkCdcShim shim = load();

    public static FlinkCdcShim getShim() {
        return shim;
    }

    static FlinkCdcShim load() {
        ServiceLoader<FlinkCdcShim> loader = ServiceLoader.load(FlinkCdcShim.class);
        List<FlinkCdcShim> shims = new ArrayList<>();
        for (FlinkCdcShim s : loader) {
            shims.add(s);
        }
        if (shims.isEmpty()) {
            throw new IllegalStateException(
                    "No FlinkCdcShim implementation found. "
                            + "Please check META-INF/services/org.apache.paimon.flink.action.cdc.shims.FlinkCdcShim");
        } else if (shims.size() > 1) {
            throw new IllegalStateException(
                    "Found more than one FlinkCdcShim implementation. "
                            + "Please check META-INF/services/org.apache.paimon.flink.action.cdc.shims.FlinkCdcShim");
        }
        LOG.info("Loaded Flink CDC shim: {}", shims.get(0).getClass().getSimpleName());
        return shims.get(0);
    }
}
