/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stormadvance.storm_hadoop.bolt;

import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.Map;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream.SyncFlag;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stormadvance.storm_hadoop.bolt.format.FileNameFormat;
import com.stormadvance.storm_hadoop.bolt.format.RecordFormat;
import com.stormadvance.storm_hadoop.bolt.rotation.FileRotationPolicy;
import com.stormadvance.storm_hadoop.bolt.sync.SyncPolicy;
import com.stormadvance.storm_hadoop.common.rotation.RotationAction;

/**
 * HDFS Bolt
 *
 * @author centos
 */
public class HdfsBolt extends AbstractHdfsBolt {
	private static final Logger LOG = LoggerFactory.getLogger(HdfsBolt.class);

	private transient FSDataOutputStream out;
	private RecordFormat format;
	private long offset = 0;
	public HdfsBolt withFsUrl(String fsUrl) {
		this.fsUrl = fsUrl;
		return this;
	}

	public HdfsBolt withConfigKey(String configKey) {
		this.configKey = configKey;
		return this;
	}

	public HdfsBolt withFileNameFormat(FileNameFormat fileNameFormat) {
		this.fileNameFormat = fileNameFormat;
		return this;
	}

	public HdfsBolt withRecordFormat(RecordFormat format) {
		this.format = format;
		return this;
	}

	public HdfsBolt withSyncPolicy(SyncPolicy syncPolicy) {
		this.syncPolicy = syncPolicy;
		return this;
	}

	public HdfsBolt withRotationPolicy(FileRotationPolicy rotationPolicy) {
		this.rotationPolicy = rotationPolicy;
		return this;
	}

	public HdfsBolt addRotationAction(RotationAction action) {
		this.rotationActions.add(action);
		return this;
	}

	@Override
	public void doPrepare(Map conf, TopologyContext topologyContext,
			OutputCollector collector) throws IOException {
		LOG.info("Preparing HDFS Bolt...");
		this.fs = FileSystem.get(URI.create(this.fsUrl), hdfsConfig);
	}

	public void execute(Tuple tuple) {
		try {
			byte[] bytes = this.format.format(tuple);
			synchronized (this.writeLock) {
				out.write(bytes);
				this.offset += bytes.length;

				if (this.syncPolicy.mark(tuple, this.offset)) {
					if (this.out instanceof HdfsDataOutputStream) {
						((HdfsDataOutputStream) this.out).hsync(EnumSet
								.of(SyncFlag.UPDATE_LENGTH));
					} else {
						this.out.hsync();
					}
					this.syncPolicy.reset();
				}
			}

			this.collector.ack(tuple);

			if (this.rotationPolicy.mark(tuple, this.offset)) {
				rotateOutputFile(); // synchronized
				this.offset = 0;
				this.rotationPolicy.reset();
			}
		} catch (IOException e) {
			LOG.warn("write/sync failed.", e);
			this.collector.fail(tuple);
		}
	}

	@Override
	void closeOutputFile() throws IOException {
		this.out.close();
	}

	@Override
	Path createOutputFile() throws IOException {
		Path path = new Path(this.fileNameFormat.getPath(),
				this.fileNameFormat.getName(this.rotation,
						System.currentTimeMillis()));
		this.out = this.fs.create(path);
		return path;
	}
}
