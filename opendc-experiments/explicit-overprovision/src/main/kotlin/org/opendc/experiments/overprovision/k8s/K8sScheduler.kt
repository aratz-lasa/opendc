package org.opendc.experiments.overprovision.k8s

import org.opendc.compute.api.Server
import org.opendc.compute.service.internal.HostView
import org.opendc.compute.service.scheduler.ComputeScheduler
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import org.opendc.compute.service.scheduler.weights.HostWeigher
import java.util.Random

public fun createK8sScheduler(name: String, seeder: Random): ComputeScheduler {
    val cpuAllocationRatio = 16.0
    val ramAllocationRatio = 1.5

    return when (name) {
        "random" -> FilterScheduler(
            filters = listOf(ComputeFilter(), VCpuFilter(cpuAllocationRatio), RamFilter(ramAllocationRatio)),
            weighers = emptyList(),
            subsetSize = Int.MAX_VALUE,
            random = Random(seeder.nextLong())
        )
        "overprovision" -> FilterScheduler(
            filters = listOf(ComputeFilter(), VCpuFilter(cpuAllocationRatio), RamFilter(ramAllocationRatio)),
            weighers = listOf(OverprovisionWeigher(1.0)),
            subsetSize = Int.MAX_VALUE,
            random = Random(seeder.nextLong())
        )
        else -> {throw IllegalArgumentException("Unknown policy $name")}
    }
}

public class OverprovisionWeigher(override val multiplier: Double) : HostWeigher {
    override fun getWeight(host: HostView, server: Server): Double {
        val host = host.host
        if (host is K8sNode){
            val provisionedCores = host.server?.hv?.provisionedCores
            val cpuCount = host.server?.hv?.host?.model?.cpuCount
            if (provisionedCores != null && cpuCount != null){
                return (cpuCount - provisionedCores) * multiplier
            }
        }
        return 0.0
    }

    override fun toString(): String = "OverprovisionWeigher"
}
