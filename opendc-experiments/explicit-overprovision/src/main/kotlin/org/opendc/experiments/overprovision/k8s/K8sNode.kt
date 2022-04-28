package org.opendc.experiments.overprovision.k8s

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement
import io.opentelemetry.api.metrics.ObservableLongMeasurement
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.opendc.compute.api.Flavor
import org.opendc.compute.api.Server
import org.opendc.compute.api.ServerState
import org.opendc.compute.service.internal.ClientServer
import org.opendc.compute.service.driver.Host
import org.opendc.compute.service.driver.HostListener
import org.opendc.compute.service.driver.HostModel
import org.opendc.compute.service.driver.HostState
import org.opendc.compute.simulator.SimHost
import org.opendc.compute.simulator.SimMetaWorkloadMapper
import org.opendc.compute.simulator.SimWorkloadMapper
import org.opendc.compute.simulator.internal.Guest
import org.opendc.compute.simulator.internal.GuestListener
import org.opendc.simulator.compute.SimBareMetalMachine
import org.opendc.simulator.compute.SimMachineContext
import org.opendc.simulator.compute.kernel.SimHypervisor
import org.opendc.simulator.compute.kernel.SimHypervisorProvider
import org.opendc.simulator.compute.kernel.cpufreq.PerformanceScalingGovernor
import org.opendc.simulator.compute.kernel.cpufreq.ScalingGovernor
import org.opendc.simulator.compute.kernel.interference.VmInterferenceDomain
import org.opendc.simulator.compute.model.MachineModel
import org.opendc.simulator.compute.model.MemoryUnit
import org.opendc.simulator.compute.power.ConstantPowerModel
import org.opendc.simulator.compute.power.PowerDriver
import org.opendc.simulator.compute.power.SimplePowerDriver
import org.opendc.simulator.compute.workload.SimWorkload
import org.opendc.simulator.flow.FlowEngine
import java.util.*
import kotlin.coroutines.CoroutineContext

class K8sNode(
    override val uid: UUID,
    override val name: String,
    model: MachineModel,
    override val meta: Map<String, Any>,
    context: CoroutineContext,
    engine: FlowEngine,
    meterProvider: MeterProvider,
    hypervisorProvider: SimHypervisorProvider,
    scalingGovernor: ScalingGovernor = PerformanceScalingGovernor(),
    powerDriver: PowerDriver = SimplePowerDriver(ConstantPowerModel(0.0)),
    private val mapper: SimWorkloadMapper = SimMetaWorkloadMapper(),
    private val interferenceDomain: VmInterferenceDomain? = null,
    private val optimize: Boolean = false
) : SimWorkload, Host, AutoCloseable {

    public var server: ClientServer? = null
    /**
     * The [CoroutineScope] of the host bounded by the lifecycle of the host.
     */
    private val scope: CoroutineScope = CoroutineScope(context + Job())

    /**
     * The clock instance used by the host.
     */
    private val clock = engine.clock

    /**
     * The logger instance of this server.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * The [Meter] to track metrics of the simulated host.
     */
    private val meter = meterProvider.get("org.opendc.compute.simulator")

    /**
     * The event listeners registered with this host.
     */
    private val listeners = mutableListOf<HostListener>()

    /**
     * The machine to run on.
     */
    public val machine: SimBareMetalMachine = SimBareMetalMachine(engine, model.optimize(), powerDriver)

    /**
     * The hypervisor to run multiple workloads.
     */
    public val hypervisor: SimHypervisor = hypervisorProvider
        .create(engine, scalingGovernor = null, interferenceDomain = null)

    /**
     * The virtual machines running on the hypervisor.
     */
    private val guests = HashMap<Server, Guest>()
    private val _guests = mutableListOf<Guest>()

    override val state: HostState
        get() = _state
    private var _state: HostState = HostState.UP
        set(value) {
            if (value != field) {
                listeners.forEach { it.onStateChanged(this, value) }
            }
            field = value
        }

    override val model: HostModel = HostModel(model.cpus.sumOf { it.frequency }, model.cpus.size, model.memory.sumOf { it.size })

    /**
     * The [GuestListener] that listens for guest events.
     */
    private val guestListener = object : GuestListener {
        override fun onStart(guest: Guest) {
            listeners.forEach { it.onStateChanged(this@K8sNode, guest.server, guest.state) }
        }

        override fun onStop(guest: Guest) {
            listeners.forEach { it.onStateChanged(this@K8sNode, guest.server, guest.state) }
        }
    }

    override fun canFit(server: Server): Boolean {
        val sufficientMemory = model.memoryCapacity >= server.flavor.memorySize
        val enoughCpus = model.cpuCount >= server.flavor.cpuCount
        val canFit = hypervisor.canFit(server.flavor.toMachineModel())

        return sufficientMemory && enoughCpus && canFit
    }

    override suspend fun spawn(server: Server, start: Boolean) {
        val guest = guests.computeIfAbsent(server) { key ->
            require(canFit(key)) { "Server does not fit" }

            val machine = hypervisor.newMachine(key.flavor.toMachineModel(), key.name)
            val newGuest = Guest(
                scope.coroutineContext,
                clock,
                this,
                hypervisor,
                mapper,
                guestListener,
                server,
                machine
            )

            _guests.add(newGuest)
            newGuest
        }

        if (start) {
            guest.start()
        }
    }

    override fun contains(server: Server): Boolean {
        return server in guests
    }

    override suspend fun start(server: Server) {
        val guest = requireNotNull(guests[server]) { "Unknown server ${server.uid} at host $uid" }
        guest.start()
    }

    override suspend fun stop(server: Server) {
        val guest = requireNotNull(guests[server]) { "Unknown server ${server.uid} at host $uid" }
        guest.stop()
    }

    override suspend fun delete(server: Server) {
        val guest = guests[server] ?: return
        guest.delete()
    }

    override fun addListener(listener: HostListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: HostListener) {
        listeners.remove(listener)
    }

    override fun close() {
        reset()
        scope.cancel()
        machine.cancel()
    }

    override fun hashCode(): Int = uid.hashCode()



    override fun equals(other: Any?): Boolean {
        return other is SimHost && uid == other.uid
    }

    override fun toString(): String = "SimHost[uid=$uid,name=$name,model=$model]"

    public suspend fun fail() {
        reset()

        for (guest in _guests) {
            guest.fail()
        }
    }

    public suspend fun recover() {
        updateUptime()

        // Wait for the hypervisor to launch before recovering the guests
        yield()

        for (guest in _guests) {
            guest.recover()
        }
    }

    /**
     * The [Job] that represents the machine running the hypervisor.
     */
    private var _ctx: SimMachineContext? = null

    /**
     * Reset the machine.
     */
    private fun reset() {
        updateUptime()

        // Stop the hypervisor
        _ctx?.close()
        _state = HostState.DOWN
    }

    /**
     * Convert flavor to machine model.
     */
    private fun Flavor.toMachineModel(): MachineModel {
        val originalCpu = machine.model.cpus[0]
        val cpuCapacity = (this.meta["cpu-capacity"] as? Double ?: Double.MAX_VALUE).coerceAtMost(originalCpu.frequency)
        val processingNode = originalCpu.node.copy(coreCount = cpuCount)
        val processingUnits = (0 until cpuCount).map { originalCpu.copy(id = it, node = processingNode, frequency = cpuCapacity) }
        val memoryUnits = listOf(MemoryUnit("Generic", "Generic", 3200.0, memorySize))

        return MachineModel(processingUnits, memoryUnits).optimize()
    }

    /**
     * Optimize the [MachineModel] for simulation.
     */
    private fun MachineModel.optimize(): MachineModel {
        if (!optimize) {
            return this
        }

        val originalCpu = cpus[0]
        val freq = cpus.sumOf { it.frequency }
        val processingNode = originalCpu.node.copy(coreCount = 1)
        val processingUnits = listOf(originalCpu.copy(frequency = freq, node = processingNode))

        val memorySize = memory.sumOf { it.size }
        val memoryUnits = listOf(MemoryUnit("Generic", "Generic", 3200.0, memorySize))

        return MachineModel(processingUnits, memoryUnits)
    }

    private val STATE_KEY = AttributeKey.stringKey("state")

    private val terminatedState = Attributes.of(STATE_KEY, "terminated")
    private val runningState = Attributes.of(STATE_KEY, "running")
    private val errorState = Attributes.of(STATE_KEY, "error")
    private val invalidState = Attributes.of(STATE_KEY, "invalid")

    /**
     * Helper function to collect the guest counts on this host.
     */
    private fun collectGuests(result: ObservableLongMeasurement) {
        var terminated = 0L
        var running = 0L
        var error = 0L
        var invalid = 0L

        val guests = _guests.listIterator()
        for (guest in guests) {
            when (guest.state) {
                ServerState.TERMINATED -> terminated++
                ServerState.RUNNING -> running++
                ServerState.ERROR -> error++
                ServerState.DELETED -> {
                    // Remove guests that have been deleted
                    this.guests.remove(guest.server)
                    guests.remove()
                }
                else -> invalid++
            }
        }

        result.record(terminated, terminatedState)
        result.record(running, runningState)
        result.record(error, errorState)
        result.record(invalid, invalidState)
    }

    private val _cpuLimit = machine.model.cpus.sumOf { it.frequency }

    /**
     * Helper function to collect the CPU limits of a machine.
     */
    private fun collectCpuLimit(result: ObservableDoubleMeasurement) {
        result.record(_cpuLimit)

        val guests = _guests
        for (i in guests.indices) {
            guests[i].collectCpuLimit(result)
        }
    }

    private val _activeState = Attributes.of(STATE_KEY, "active")
    private val _stealState = Attributes.of(STATE_KEY, "steal")
    private val _lostState = Attributes.of(STATE_KEY, "lost")
    private val _idleState = Attributes.of(STATE_KEY, "idle")

    /**
     * Helper function to track the CPU time of a machine.
     */
    private fun collectCpuTime(result: ObservableLongMeasurement) {
        val counters = hypervisor.counters
        counters.flush()

        result.record(counters.cpuActiveTime / 1000L, _activeState)
        result.record(counters.cpuIdleTime / 1000L, _idleState)
        result.record(counters.cpuStealTime / 1000L, _stealState)
        result.record(counters.cpuLostTime / 1000L, _lostState)

        val guests = _guests
        for (i in guests.indices) {
            guests[i].collectCpuTime(result)
        }
    }

    private var _lastReport = clock.millis()

    /**
     * Helper function to track the uptime of a machine.
     */
    private fun updateUptime() {
        val now = clock.millis()
        val duration = now - _lastReport
        _lastReport = now

        if (_state == HostState.UP) {
            _uptime += duration
        } else if (_state == HostState.DOWN && scope.isActive) {
            // Only increment downtime if the machine is in a failure state
            _downtime += duration
        }

        val guests = _guests
        for (i in guests.indices) {
            guests[i].updateUptime(duration)
        }
    }

    private var _uptime = 0L
    private var _downtime = 0L
    private val _upState = Attributes.of(STATE_KEY, "up")
    private val _downState = Attributes.of(STATE_KEY, "down")

    /**
     * Helper function to track the uptime of a machine.
     */
    private fun collectUptime(result: ObservableLongMeasurement) {
        updateUptime()

        result.record(_uptime, _upState)
        result.record(_downtime, _downState)

        val guests = _guests
        for (i in guests.indices) {
            guests[i].collectUptime(result)
        }
    }

    private var _bootTime = Long.MIN_VALUE

    /**
     * Helper function to track the boot time of a machine.
     */
    private fun collectBootTime(result: ObservableLongMeasurement) {
        if (_bootTime != Long.MIN_VALUE) {
            result.record(_bootTime)
        }

        val guests = _guests
        for (i in guests.indices) {
            guests[i].collectBootTime(result)
        }
    }

    override fun onStart(ctx: SimMachineContext) {
        hypervisor.onStart(ctx)
    }

    override fun onStop(ctx: SimMachineContext) {
        hypervisor.onStop(ctx)
    }

    public override fun predictInterference(interferenceId: String) : Double{
        if (interferenceDomain != null){
            val key = interferenceDomain.createKey(interferenceId)
            if (key != null){
                interferenceDomain.join(key)
                val interference = interferenceDomain.apply(key, 1.0)
                interferenceDomain.leave(key)
                return interference
            }
            return 0.0
        }
        return 0.0
    }
}
