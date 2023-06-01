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

package org.opendc.web.runner

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.opendc.compute.workload.*
import org.opendc.compute.workload.telemetry.SdkTelemetryManager
import org.opendc.compute.workload.topology.HostSpec
import org.opendc.compute.workload.topology.Topology
import org.opendc.compute.workload.topology.apply
import org.opendc.compute.workload.util.VmInterferenceModelReader
import org.opendc.simulator.compute.kernel.interference.VmInterferenceModel
import org.opendc.simulator.compute.model.MachineModel
import org.opendc.simulator.compute.model.MemoryUnit
import org.opendc.simulator.compute.model.ProcessingNode
import org.opendc.simulator.compute.model.ProcessingUnit
import org.opendc.simulator.compute.power.LinearPowerModel
import org.opendc.simulator.compute.power.SimplePowerDriver
import org.opendc.simulator.core.runBlockingSimulation
import org.opendc.telemetry.compute.collectServiceMetrics
import org.opendc.telemetry.sdk.metrics.export.CoroutineMetricReader
import org.opendc.web.client.auth.OpenIdAuthController
import org.opendc.web.client.runner.OpenDCRunnerClient
import org.opendc.web.proto.runner.Job
import org.opendc.web.proto.runner.Scenario
import java.io.File
import java.net.URI
import java.time.Duration
import java.util.*
import org.opendc.web.proto.runner.Topology as ClientTopology

private val logger = KotlinLogging.logger {}

/**
 * Represents the CLI command for starting the OpenDC web runner.
 */
class RunnerCli : CliktCommand(name = "runner") {
    /**
     * The URL to the OpenDC API.
     */
    private val apiUrl by option(
        "--api-url",
        help = "url to the OpenDC API",
        envvar = "OPENDC_API_URL"
    )
        .convert { URI(it) }
        .default(URI("https://api.opendc.org/v2"))

    /**
     * The auth domain to use.
     */
    private val authDomain by option(
        "--auth-domain",
        help = "auth domain of the OpenDC API",
        envvar = "AUTH0_DOMAIN"
    )
        .required()

    /**
     * The auth domain to use.
     */
    private val authAudience by option(
        "--auth-audience",
        help = "auth audience of the OpenDC API",
        envvar = "AUTH0_AUDIENCE"
    )
        .required()

    /**
     * The auth client ID to use.
     */
    private val authClientId by option(
        "--auth-id",
        help = "auth client id of the OpenDC API",
        envvar = "AUTH0_CLIENT_ID"
    )
        .required()

    /**
     * The auth client secret to use.
     */
    private val authClientSecret by option(
        "--auth-secret",
        help = "auth client secret of the OpenDC API",
        envvar = "AUTH0_CLIENT_SECRET"
    )
        .required()

    /**
     * The path to the traces directory.
     */
    private val tracePath by option(
        "--traces",
        help = "path to the directory containing the traces",
        envvar = "OPENDC_TRACES"
    )
        .file(canBeFile = false)
        .defaultLazy { File("traces/") }

    /**
     * The maximum duration of a single experiment run.
     */
    private val runTimeout by option(
        "--run-timeout",
        help = "maximum duration of experiment in seconds",
        envvar = "OPENDC_RUN_TIMEOUT"
    )
        .long()
        .default(60L * 3) // Experiment may run for a maximum of three minutes

    /**
     * Run a simulation job.
     */
    private suspend fun runJob(job: Job, topology: Topology): List<WebComputeMetricExporter.Result> {
        val id = job.id
        val scenario = job.scenario

        logger.info { "Constructing performance interference model" }

        val workloadLoader = ComputeWorkloadLoader(tracePath)
        val interferenceModel = let {
            val path = tracePath.resolve(scenario.workload.trace.id).resolve("performance-interference-model.json")
            val enabled = scenario.phenomena.interference

            if (!enabled || !path.exists()) {
                return@let null
            }

            VmInterferenceModelReader().read(path.inputStream())
        }

        val results = (0 until scenario.portfolio.targets.repeats).map { repeat ->
            logger.info { "Starting repeat $repeat" }
            withTimeout(runTimeout * 1000) {
                runRepeat(scenario, repeat, topology, workloadLoader, interferenceModel?.withSeed(repeat.toLong()))
            }
        }

        logger.info { "Finished simulation for scenario $id" }

        return results
    }

    /**
     * Run a single repeat.
     */
    private suspend fun runRepeat(
        scenario: Scenario,
        repeat: Int,
        topology: Topology,
        workloadLoader: ComputeWorkloadLoader,
        interferenceModel: VmInterferenceModel?
    ): WebComputeMetricExporter.Result {
        val exporter = WebComputeMetricExporter()

        try {
            runBlockingSimulation(true) {
                val workloadName = scenario.workload.trace.id
                val workloadFraction = scenario.workload.samplingFraction

                val seeder = Random(repeat.toLong())

                val phenomena = scenario.phenomena
                val computeScheduler = createComputeScheduler(scenario.schedulerName, seeder)
                val workload = trace(workloadName).sampleByLoad(workloadFraction)

                val failureModel =
                    if (phenomena.failures)
                        grid5000(Duration.ofDays(7))
                    else
                        null

                val telemetry = SdkTelemetryManager(clock)
                val simulator = ComputeServiceHelper(
                    coroutineContext,
                    clock,
                    telemetry,
                    computeScheduler,
                    failureModel,
                    interferenceModel
                )

                telemetry.registerMetricReader(CoroutineMetricReader(this, exporter, exportInterval = Duration.ofHours(1)))

                try {
                    // Instantiate the topology onto the simulator
                    simulator.apply(topology)
                    // Run workload trace
                    simulator.run(workload.resolve(workloadLoader, seeder), seeder.nextLong())

                    val serviceMetrics = collectServiceMetrics(telemetry.metricProducer)
                    logger.debug {
                        "Scheduler " +
                            "Success=${serviceMetrics.attemptsSuccess} " +
                            "Failure=${serviceMetrics.attemptsFailure} " +
                            "Error=${serviceMetrics.attemptsError} " +
                            "Pending=${serviceMetrics.serversPending} " +
                            "Active=${serviceMetrics.serversActive}"
                    }
                } finally {
                    simulator.close()
                    telemetry.close()
                }
            }
        } catch (cause: Throwable) {
            logger.warn(cause) { "Experiment failed" }
        }

        return exporter.getResult()
    }

    private val POLL_INTERVAL = 30000L // ms = 30 s
    private val HEARTBEAT_INTERVAL = 60000L // ms = 1 min

    override fun run(): Unit = runBlocking(Dispatchers.Default) {
        logger.info { "Starting OpenDC web runner" }

        val client = OpenDCRunnerClient(baseUrl = apiUrl, OpenIdAuthController(authDomain, authClientId, authClientSecret, authAudience))
        val manager = ScenarioManager(client)

        logger.info { "Watching for queued scenarios" }

        while (true) {
            val job = manager.findNext()
            if (job == null) {
                delay(POLL_INTERVAL)
                continue
            }

            val id = job.id

            logger.info { "Found queued scenario $id: attempting to claim" }

            if (!manager.claim(id)) {
                logger.info { "Failed to claim scenario" }
                continue
            }

            coroutineScope {
                // Launch heartbeat process
                val heartbeat = launch {
                    while (true) {
                        manager.heartbeat(id)
                        delay(HEARTBEAT_INTERVAL)
                    }
                }

                try {
                    val environment = convert(job.scenario.topology)
                    val results = runJob(job, environment)

                    logger.info { "Writing results to database" }

                    manager.finish(id, results)

                    logger.info { "Successfully finished scenario $id" }
                } catch (e: Exception) {
                    logger.error(e) { "Scenario failed to finish" }
                    manager.fail(id)
                } finally {
                    heartbeat.cancel()
                }
            }
        }
    }

    /**
     * Convert the specified [topology] into an [Topology] understood by OpenDC.
     */
    private fun convert(topology: ClientTopology): Topology {
        return object : Topology {

            override fun resolve(): List<HostSpec> {
                val res = mutableListOf<HostSpec>()
                val random = Random(0)

                val machines = topology.rooms.asSequence()
                    .flatMap { room ->
                        room.tiles.flatMap { tile ->
                            val rack = tile.rack
                            rack?.machines?.map { machine -> rack to machine } ?: emptyList()
                        }
                    }
                for ((rack, machine) in machines) {
                    val clusterId = rack.id
                    val position = machine.position

                    val processors = machine.cpus.flatMap { cpu ->
                        val cores = cpu.numberOfCores
                        val speed = cpu.clockRateMhz
                        // TODO Remove hard coding of vendor
                        val node = ProcessingNode("Intel", "amd64", cpu.name, cores)
                        List(cores) { coreId ->
                            ProcessingUnit(node, coreId, speed)
                        }
                    }
                    val memoryUnits = machine.memory.map { memory ->
                        MemoryUnit(
                            "Samsung",
                            memory.name,
                            memory.speedMbPerS,
                            memory.sizeMb.toLong()
                        )
                    }

                    val energyConsumptionW = machine.cpus.sumOf { it.energyConsumptionW }
                    val powerModel = LinearPowerModel(2 * energyConsumptionW, energyConsumptionW * 0.5)
                    val powerDriver = SimplePowerDriver(powerModel)

                    val spec = HostSpec(
                        UUID(random.nextLong(), random.nextLong()),
                        "node-$clusterId-$position",
                        mapOf("cluster" to clusterId),
                        MachineModel(processors, memoryUnits),
                        powerDriver
                    )

                    res += spec
                }

                return res
            }

            override fun toString(): String = "WebRunnerTopologyFactory"
        }
    }
}

/**
 * Main entry point of the runner.
 */
fun main(args: Array<String>): Unit = RunnerCli().main(args)
