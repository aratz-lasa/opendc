package org.opendc.experiments.overprovision

import mu.KotlinLogging
import org.opendc.compute.workload.*
import org.opendc.compute.workload.export.parquet.ParquetComputeMetricExporter
import org.opendc.compute.workload.telemetry.SdkTelemetryManager
import org.opendc.compute.workload.topology.apply
import org.opendc.compute.workload.util.VmInterferenceModelReader
import org.opendc.harness.dsl.Experiment
import org.opendc.harness.dsl.anyOf
import org.opendc.simulator.core.runBlockingSimulation
import org.opendc.telemetry.compute.collectServiceMetrics
import org.opendc.telemetry.sdk.metrics.export.CoroutineMetricReader
import java.io.File
import java.util.*
import org.opendc.experiments.capelin.model.Topology
import org.opendc.experiments.capelin.topology.clusterTopology
import org.opendc.telemetry.compute.ComputeMetricExporter
import org.opendc.telemetry.compute.table.HostTableReader
import org.opendc.telemetry.compute.table.ServiceData
import org.opendc.telemetry.compute.table.ServiceTableReader
import java.time.Instant


public class NormalExperiment : Experiment(name = "small") {
    private val logger = KotlinLogging.logger {}
    val topology: Topology by anyOf(
        Topology("base")
    )
    val workloadTrace: ComputeWorkload by anyOf(
        trace("bitbrains-small").sampleByLoad(1.0),
    )
    val tenantAmount: Int by anyOf(
        1
    )
    private val vmPlacements by anyOf(emptyMap<String, String>())
    private val allocationPolicy: String by anyOf(
        "random"
    )
    private val workloadLoader = ComputeWorkloadLoader(File("src/main/resources/trace"))

    override fun doRun(repeat: Int) : Unit = runBlockingSimulation {
        val seeder = Random(repeat.toLong())
        val workload = workloadTrace.resolve(workloadLoader, seeder)
        val exporter = TestComputeMetricExporter()
        val topology = clusterTopology(File("src/main/resources/topology", "${topology.name}.txt"))

        val telemetry = SdkTelemetryManager(clock)

        val computeScheduler = createComputeScheduler(allocationPolicy, seeder, vmPlacements)

        val perfInterferenceInput = checkNotNull(NormalExperiment::class.java.getResourceAsStream("/trace/bitbrains-small/bitbrains-perf-interference.json"))
        val performanceInterferenceModel =
            VmInterferenceModelReader()
                .read(perfInterferenceInput)
                .withSeed(seeder.nextLong())

        telemetry.registerMetricReader(CoroutineMetricReader(this, exporter))

        val simulator = ComputeServiceHelper(
            coroutineContext,
            clock,
            telemetry,
            computeScheduler,
            interferenceModel = performanceInterferenceModel
        )

        try{
            simulator.apply(topology)
            simulator.run(workload, seeder.nextLong())

            val serviceMetrics = collectServiceMetrics(telemetry.metricProducer)
            logger.info {
                "Scheduler " +
                    "Success=${serviceMetrics.attemptsSuccess} " +
                    "Failure=${serviceMetrics.attemptsFailure} " +
                    "Error=${serviceMetrics.attemptsError} " +
                    "Pending=${serviceMetrics.serversPending} " +
                    "Active=${serviceMetrics.serversActive}"
            }
            logger.info {
                "Host " +
                    "Idle time=${exporter.idleTime} " +
                    "Active time=${exporter.activeTime} " +
                    "Steal time=${exporter.stealTime} " +
                    "Lost time=${exporter.lostTime} " +
                    "Energy usage=${exporter.energyUsage}"
            }
        }finally {
            simulator.close()
            telemetry.close()
        }
    }

    /**
     * Obtain the trace reader for the test.
     */
    private fun createTestWorkload(fraction: Double, seed: Int = 0): List<VirtualMachine> {
        val source = trace("bitbrains-small").sampleByLoad(fraction)
        return source.resolve(workloadLoader, Random(seed.toLong()))
    }
    private fun createTopology(name: String = "topology"): org.opendc.compute.workload.topology.Topology {
        val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/env/$name.txt"))
        return stream.use { clusterTopology(stream) }
    }
}


class TestComputeMetricExporter : ComputeMetricExporter() {
    var serviceMetrics: ServiceData = ServiceData(Instant.ofEpochMilli(0), 0, 0, 0, 0, 0, 0, 0)
    var idleTime = 0L
    var activeTime = 0L
    var stealTime = 0L
    var lostTime = 0L
    var energyUsage = 0.0
    var uptime = 0L

    override fun record(reader: ServiceTableReader) {
        serviceMetrics = ServiceData(
            reader.timestamp,
            reader.hostsUp,
            reader.hostsDown,
            reader.serversPending,
            reader.serversActive,
            reader.attemptsSuccess,
            reader.attemptsFailure,
            reader.attemptsError
        )
    }

    override fun record(reader: HostTableReader) {
        idleTime += reader.cpuIdleTime
        activeTime += reader.cpuActiveTime
        stealTime += reader.cpuStealTime
        lostTime += reader.cpuLostTime
        energyUsage += reader.powerTotal
        uptime += reader.uptime
    }
}
