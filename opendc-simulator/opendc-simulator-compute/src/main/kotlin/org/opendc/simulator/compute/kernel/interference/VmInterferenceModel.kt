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

package org.opendc.simulator.compute.kernel.interference

import org.opendc.simulator.flow.interference.InterferenceKey
import java.util.*
import kotlin.collections.ArrayList

/**
 * An interference model that models the resource interference between virtual machines on a host.
 *
 * @param targets The target load of each group.
 * @param scores The performance score of each group.
 * @param members The members belonging to each group.
 * @param membership The identifier of each key.
 * @param size The number of groups.
 * @param seed The seed to use for randomly selecting the virtual machines that are affected.
 */
public class VmInterferenceModel private constructor(
    private val targets: DoubleArray,
    private val scores: DoubleArray,
    private val idMapping: Map<String, Int>,
    private val members: Array<IntArray>,
    private val membership: Array<IntArray>,
    private val size: Int,
    seed: Long,
) {
    /**
     * A [SplittableRandom] used for selecting the virtual machines that are affected.
     */
    private val random = SplittableRandom(seed)

    /**
     * Construct a new [VmInterferenceDomain].
     */
    public fun newDomain(): VmInterferenceDomain = InterferenceDomainImpl(targets, scores, idMapping, members, membership, random)

    /**
     * Create a copy of this model with a different seed.
     */
    public fun withSeed(seed: Long): VmInterferenceModel {
        return VmInterferenceModel(targets, scores, idMapping, members, membership, size, seed)
    }

    public companion object {
        /**
         * Construct a [Builder] instance.
         */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * Builder class for a [VmInterferenceModel]
     */
    public class Builder internal constructor() {
        /**
         * The initial capacity of the builder.
         */
        private val INITIAL_CAPACITY = 256

        /**
         * The target load of each group.
         */
        private var _targets = DoubleArray(INITIAL_CAPACITY) { Double.POSITIVE_INFINITY }

        /**
         * The performance score of each group.
         */
        private var _scores = DoubleArray(INITIAL_CAPACITY) { Double.POSITIVE_INFINITY }

        /**
         * The members of each group.
         */
        private var _members = ArrayList<Set<String>>(INITIAL_CAPACITY)

        /**
         * The mapping from member to group id.
         */
        private val ids = TreeSet<String>()

        /**
         * The number of groups in the model.
         */
        private var size = 0

        /**
         * Add the specified group to the model.
         */
        public fun addGroup(members: Set<String>, targetLoad: Double, score: Double): Builder {
            val size = size

            if (size == _targets.size) {
                grow()
            }

            _targets[size] = targetLoad
            _scores[size] = score
            _members.add(members)
            ids.addAll(members)

            this.size++

            return this
        }

        /**
         * Build the [VmInterferenceModel].
         */
        public fun build(seed: Long = 0): VmInterferenceModel {
            val size = size
            val targets = _targets
            val scores = _scores
            val members = _members

            val indices = Array(size) { it }
            indices.sortWith(
                Comparator { l, r ->
                    var cmp = targets[l].compareTo(targets[r]) // Order by target load
                    if (cmp != 0) {
                        return@Comparator cmp
                    }

                    cmp = scores[l].compareTo(scores[r]) // Higher penalty first (this means lower performance score first)
                    if (cmp != 0)
                        cmp
                    else
                        l.compareTo(r)
                }
            )

            val newTargets = DoubleArray(size)
            val newScores = DoubleArray(size)
            val newMembers = arrayOfNulls<IntArray>(size)

            var nextId = 0
            val idMapping = ids.associateWith { nextId++ }
            val membership = ids.associateWithTo(TreeMap()) { ArrayList<Int>() }

            for ((group, j) in indices.withIndex()) {
                newTargets[group] = targets[j]
                newScores[group] = scores[j]
                val groupMembers = members[j]
                val newGroupMembers = groupMembers.map { idMapping.getValue(it) }.toIntArray()

                newGroupMembers.sort()
                newMembers[group] = newGroupMembers

                for (member in groupMembers) {
                    membership.getValue(member).add(group)
                }
            }

            @Suppress("UNCHECKED_CAST")
            return VmInterferenceModel(
                newTargets,
                newScores,
                idMapping,
                newMembers as Array<IntArray>,
                membership.map { it.value.toIntArray() }.toTypedArray(),
                size,
                seed
            )
        }

        /**
         * Helper function to grow the capacity of the internal arrays.
         */
        private fun grow() {
            val oldSize = _targets.size
            val newSize = oldSize + (oldSize shr 1)

            _targets = _targets.copyOf(newSize)
            _scores = _scores.copyOf(newSize)
        }
    }

    /**
     * Internal implementation of [VmInterferenceDomain].
     */
    private class InterferenceDomainImpl(
        private val targets: DoubleArray,
        private val scores: DoubleArray,
        private val idMapping: Map<String, Int>,
        private val members: Array<IntArray>,
        private val membership: Array<IntArray>,
        private val random: SplittableRandom
    ) : VmInterferenceDomain {
        /**
         * Keys registered with this domain.
         */
        private val keys = HashMap<Int, InterferenceKeyImpl>()

        private val idMappingReverse = mutableMapOf<Int, String>().also {
            idMapping.forEach{(k,v)->it.put(v,k)}
        }.toMap()

        /**
         * The set of keys active in this domain.
         */
        private val activeKeys = ArrayList<InterferenceKeyImpl>()

        /**
         * The set of callbacks to be called when performance score changes
         */
        private val listeners = ArrayList<InterferenceListener>()

        override fun createKey(id: String): InterferenceKey? {
            val intId = idMapping[id] ?: return null
            return keys.computeIfAbsent(intId) { InterferenceKeyImpl(intId) }
        }

        override fun removeKey(key: InterferenceKey) {
            if (key !is InterferenceKeyImpl) {
                return
            }

            if (activeKeys.remove(key)) {
                computeActiveGroups(key.id)
            }

            keys.remove(key.id)
        }

        fun getKey(id: String) : InterferenceKey?{
            val intId = idMapping[id] ?: return null
            return keys.get(intId)
        }

        override fun join(key: InterferenceKey) {
            if (key !is InterferenceKeyImpl) {
                return
            }

            if (key.acquire()) {
                val pos = activeKeys.binarySearch(key)
                if (pos < 0) {
                    activeKeys.add(-pos - 1, key)
                }
                computeActiveGroups(key.id)
            }
        }

        override fun leave(key: InterferenceKey) {
            if (key is InterferenceKeyImpl && key.release()) {
                activeKeys.remove(key)
                computeActiveGroups(key.id)
            }
        }

        override fun addListener(listener: InterferenceListener) {
            listeners.add(listener)
        }

        override fun apply(key: InterferenceKey?, load: Double): Double {
            if (key == null || key !is InterferenceKeyImpl) {
                return 1.0
            }

            val groups = key.groups
            val groupSize = groups.size

            if (groupSize == 0) {
                return 1.0
            }

            val targets = targets
            val scores = scores
            var low = 0
            var high = groups.size - 1

            var group = -1
            var score = 1.0

            // Perform binary search over the groups based on target load
            while (low <= high) {
                val mid = low + high ushr 1
                val midGroup = groups[mid]
                val target = targets[midGroup]
                if (target < load) {
                    low = mid + 1
                    group = midGroup
                    score = scores[midGroup]
                } else if (target > load) {
                    high = mid - 1
                } else {
                    group = midGroup
                    score = scores[midGroup]
                    break
                }
            }

            return if (group >= 0 && random.nextInt(members[group].size) == 0) {
                //callListeners(key, score)
                score
            } else {
                //callListeners(key, 1.0)
                1.0
            }
        }

        fun callListeners(key: InterferenceKey, score: Double){
            if (key !is InterferenceKeyImpl) {
                return
            }
            val serverName = idMappingReverse[key.id]
            serverName?.let{
                for (l in listeners){
                    l.onInterference(Pair(serverName, score))
                }
            }
        }

        override fun toString(): String = "VmInterferenceDomain"

        /**
         * Queue of participants that will be removed or added to the active groups.
         */
        private val _participants = ArrayDeque<InterferenceKeyImpl>()

        /**
         * (Re-)Compute the active groups.
         */
        private fun computeActiveGroups(id: Int) {
            val activeKeys = activeKeys
            val groups = membership[id]

            if (activeKeys.isEmpty()) {
                return
            }

            val members = members
            val participants = _participants

            for (group in groups) {
                val groupMembers = members[group]

                var i = 0
                var j = 0
                var intersection = 0

                // Compute the intersection of the group members and the current active members
                while (i < groupMembers.size && j < activeKeys.size) {
                    val l = groupMembers[i]
                    val rightEntry = activeKeys[j]
                    val r = rightEntry.id

                    if (l < r) {
                        i++
                    } else if (l > r) {
                        j++
                    } else {
                        participants.add(rightEntry)
                        intersection++

                        i++
                        j++
                    }
                }

                while (true) {
                    val participant = participants.poll() ?: break
                    val participantGroups = participant.groups
                    if (intersection <= 1) {
                        participantGroups.remove(group)
                    } else {
                        val pos = participantGroups.binarySearch(group)
                        if (pos < 0) {
                            participantGroups.add(-pos - 1, group)
                        }
                    }
                }
            }
        }
    }

    /**
     * An interference key.
     *
     * @param id The identifier of the member.
     */
    private class InterferenceKeyImpl(@JvmField val id: Int) : InterferenceKey, Comparable<InterferenceKeyImpl> {
        /**
         * The active groups to which the key belongs.
         */
        @JvmField val groups: MutableList<Int> = ArrayList()

        /**
         * The number of users of the interference key.
         */
        private var refCount: Int = 0

        /**
         * Join the domain.
         */
        fun acquire(): Boolean = refCount++ <= 0

        /**
         * Leave the domain.
         */
        fun release(): Boolean = --refCount <= 0

        override fun compareTo(other: InterferenceKeyImpl): Int = id.compareTo(other.id)
    }
}
