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

@file:JvmName("ResourceColumns")
package org.opendc.trace

import java.time.Instant

/**
 * Identifier of the resource.
 */
@JvmField
public val RESOURCE_ID: TableColumn<String> = column("resource:id")

/**
 * The cluster to which the resource belongs.
 */
@JvmField
public val RESOURCE_CLUSTER_ID: TableColumn<String> = column("resource:cluster_id")

/**
 * Start time for the resource.
 */
@JvmField
public val RESOURCE_START_TIME: TableColumn<Instant> = column("resource:start_time")

/**
 * End time for the resource.
 */
@JvmField
public val RESOURCE_STOP_TIME: TableColumn<Instant> = column("resource:stop_time")

/**
 * Number of CPUs for the resource.
 */
@JvmField
public val RESOURCE_CPU_COUNT: TableColumn<Int> = column("resource:cpu_count")

/**
 * Total CPU capacity of the resource in MHz.
 */
@JvmField
public val RESOURCE_CPU_CAPACITY: TableColumn<Double> = column("resource:cpu_capacity")

/**
 * Total CPU utilization of the resource in %.
 */
@JvmField
public val RESOURCE_CPU_UTILIZATION: TableColumn<Double> = column("resource:cpu_utilization")

/**
 * Memory capacity for the resource in KB.
 */
@JvmField
public val RESOURCE_MEM_CAPACITY: TableColumn<Double> = column("resource:mem_capacity")
