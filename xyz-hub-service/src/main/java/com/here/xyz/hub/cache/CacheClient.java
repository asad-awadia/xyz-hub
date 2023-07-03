/*
 * Copyright (C) 2017-2023 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.hub.cache;

import io.vertx.core.Future;

public interface CacheClient {

	Future<byte[]> get(String key);

	/**
	 *
	 * @param key
	 * @param value
	 * @param ttl The live time of the cache-record in seconds
	 */
	void set(String key, byte[] value, long ttl);

	void remove(String key);

	static CacheClient getInstance() {
		return new MultiLevelCacheClient(InMemoryCacheClient.getInstance(), RedisCacheClient.getInstance());
	}

	void shutdown();

}
