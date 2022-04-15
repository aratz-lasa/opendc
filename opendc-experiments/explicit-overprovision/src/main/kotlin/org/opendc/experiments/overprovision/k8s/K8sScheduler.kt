package org.opendc.experiments.overprovision.k8s

import org.opendc.compute.service.scheduler.ComputeScheduler
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import java.util.Random

public fun createK8sScheduler(name: String, seeder: Random, placements: Map<String, String> = emptyMap()): ComputeScheduler {
    val cpuAllocationRatio = 16.0
    val ramAllocationRatio = 1.5

    return FilterScheduler(
        filters = listOf(ComputeFilter(), VCpuFilter(cpuAllocationRatio), RamFilter(ramAllocationRatio)),
        weighers = emptyList(),
        subsetSize = Int.MAX_VALUE,
        random = Random(seeder.nextLong())
    )
}
