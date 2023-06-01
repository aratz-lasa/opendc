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

package org.opendc.simulator.flow.source

import org.opendc.simulator.flow.FlowConnection
import org.opendc.simulator.flow.FlowSource
import kotlin.math.roundToLong



/**
 * A [FlowSource] that contains a fixed [amount] and is pushed with a given [utilization].
 */
public class FixedFlowSource(private val amount: Double, private val utilization: Double, private val name :String = "") : FlowSource {

    public var it : Int = 0

    init {
        require(amount >= 0.0) { "Amount must be positive" }
        //require(utilization > 0.0) { "Utilization must be positive" }
        // allow 0 utilization
        require(utilization >= 0.0) { "Utilization must be positive" }
    }

    public var remainingAmount: Double = amount
    private var lastPull: Long = 0L
    public var lastConn: FlowConnection? = null

    override fun onStart(conn: FlowConnection, now: Long) {
        lastPull = now
    }

    override fun onPull(conn: FlowConnection, now: Long): Long {
        lastConn = conn
        val lastPull = lastPull
        this.lastPull = now
        val delta = (now - lastPull).coerceAtLeast(0)

        val consumed = conn.rate * delta / 1000.0
        val limit = conn.capacity * utilization

        remainingAmount -= consumed

        val duration = (remainingAmount / limit * 1000).roundToLong()

        return if (remainingAmount > 0 && duration > 0) {
            conn.push(limit)
            duration
        } else {
            println("CLOSED FLOW $remainingAmount $lastPull $consumed")
            conn.close()
            Long.MAX_VALUE
        }
    }
}
