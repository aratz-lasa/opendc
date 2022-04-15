package org.opendc.experiments.overprovision

import mu.KotlinLogging
import org.opendc.compute.workload.*
import org.opendc.compute.workload.export.parquet.ParquetComputeMetricExporter
import org.opendc.compute.workload.telemetry.SdkTelemetryManager
import org.opendc.harness.dsl.Experiment
import org.opendc.harness.dsl.anyOf
import org.opendc.simulator.core.runBlockingSimulation
import org.opendc.telemetry.compute.collectServiceMetrics
import org.opendc.telemetry.sdk.metrics.export.CoroutineMetricReader
import java.io.File
import java.util.*
import org.opendc.experiments.capelin.model.Topology
import org.opendc.experiments.capelin.topology.clusterTopology
import org.opendc.experiments.overprovision.k8s.MultitenantComputeService


public class ExplicitOverprovisionExperiment : Experiment(name = "small") {
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
        val exporter = ParquetComputeMetricExporter(
            File("output"),
            "scenario_id=$id.run_id=$repeat",
            4096
        )
        val topology = clusterTopology(File("src/main/resources/topology", "${topology.name}.txt"))

        val telemetry = SdkTelemetryManager(clock)

        val computeScheduler = createComputeScheduler(allocationPolicy, seeder, vmPlacements)

        telemetry.registerMetricReader(CoroutineMetricReader(this, exporter))

        val runner = MultitenantComputeService(
            coroutineContext,
            clock,
            telemetry,
            computeScheduler,
        )

        try{
            runner.apply(topology)
            runner.run(tenantAmount, workload, seeder.nextLong())

            val serviceMetrics = collectServiceMetrics(telemetry.metricProducer)
            logger.debug {
                "Scheduler " +
                    "Success=${serviceMetrics.attemptsSuccess} " +
                    "Failure=${serviceMetrics.attemptsFailure} " +
                    "Error=${serviceMetrics.attemptsError} " +
                    "Pending=${serviceMetrics.serversPending} " +
                    "Active=${serviceMetrics.serversActive}"
            }
        }finally {
            runner.close()
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
