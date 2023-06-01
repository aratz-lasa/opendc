/*
 * Copyright (c) 2021 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.experiments.metadata.trace.format

import org.apache.avro.Schema
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.opendc.trace.*
import org.opendc.trace.spi.TableDetails
import org.opendc.trace.spi.TraceFormat
import org.opendc.trace.util.parquet.LocalOutputFile
import org.opendc.trace.util.parquet.LocalParquetReader
import org.opendc.trace.util.parquet.TIMESTAMP_SCHEMA
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [TraceFormat] implementation of the OpenDC virtual machine trace format.
 */
public class OdcVmTraceFormat : TraceFormat {
    /**
     * The name of this trace format.
     */
    override val name: String = "opendc-vm"

    override fun create(path: Path) {
        // Construct directory containing the trace files
        Files.createDirectories(path)

        val tables = getTables(path)

        for (table in tables) {
            val writer = newWriter(path, table)
            writer.close()
        }
    }

    override fun getTables(path: Path): List<String> = listOf(TABLE_RESOURCES, TABLE_RESOURCE_STATES)

    override fun getDetails(path: Path, table: String): TableDetails {
        return when (table) {
            TABLE_RESOURCES -> TableDetails(
                listOf(
                    RESOURCE_ID,
                    RESOURCE_START_TIME,
                    RESOURCE_STOP_TIME,
                    RESOURCE_CPU_COUNT,
                    RESOURCE_CPU_CAPACITY,
                    RESOURCE_MEM_CAPACITY,
                )
            )
            TABLE_RESOURCE_STATES -> TableDetails(
                listOf(
                    RESOURCE_ID,
                    RESOURCE_STATE_TIMESTAMP,
                    RESOURCE_STATE_DURATION,
                    RESOURCE_CPU_COUNT,
                    RESOURCE_STATE_CPU_USAGE,
                ),
                listOf(RESOURCE_ID, RESOURCE_STATE_TIMESTAMP)
            )
            else -> throw IllegalArgumentException("Table $table not supported")
        }
    }

    override fun newReader(path: Path, table: String): TableReader {
        return when (table) {
            TABLE_RESOURCES -> {
                val reader = LocalParquetReader<GenericRecord>(path.resolve("tasks.parquet"))
                OdcVmResourceTableReader(reader)
            }
            else -> throw IllegalArgumentException("Table $table not supported")
        }
    }

    override fun newWriter(path: Path, table: String): TableWriter {
        throw IllegalArgumentException("Table $table not supported")
    }

    public companion object {
        /**
         * Schema for the resources table in the trace.
         */
        @JvmStatic
        public val RESOURCES_SCHEMA: Schema = SchemaBuilder
            .record("resource")
            .namespace("org.opendc.trace.opendc")
            .fields()
            .requiredString("id")
            .name("start_time").type(TIMESTAMP_SCHEMA).noDefault()
            .name("stop_time").type(TIMESTAMP_SCHEMA).noDefault()
            .requiredInt("cpu_count")
            .requiredDouble("cpu_capacity")
            .requiredLong("mem_capacity")
            .endRecord()

        /**
         * Schema for the resource states table in the trace.
         */
        @JvmStatic
        public val RESOURCE_STATES_SCHEMA: Schema = SchemaBuilder
            .record("resource_state")
            .namespace("org.opendc.trace.opendc")
            .fields()
            .requiredString("id")
            .name("timestamp").type(TIMESTAMP_SCHEMA).noDefault()
            .requiredLong("duration")
            .requiredInt("cpu_count")
            .requiredDouble("cpu_usage")
            .endRecord()
    }
}
