package dev.otherlode.benchmark

/**
 * Which host CPUs each container is pinned to, as Docker cpuset strings such as `0` or `1-2`.
 *
 * The split follows the host's CPU count `n`: PetClinic gets `max(1, n / 4)` cores, at most
 * [MAX_PETCLINIC_CORES], Postgres and the collector share `max(1, n / 4)`, and k6 gets every core
 * left over. A 4-CPU runner gives PetClinic core 0, k6 cores 1-2 and Postgres with the collector core
 * 3; 8 CPUs give 2, 4 and 2. PetClinic's cores overlap no other container's, so its throughput is
 * limited by its own cores and not by the client or the database. Its cores are capped so one run's
 * numbers compare with another's on a larger host, and so a closed loop of [virtualUsers] can keep
 * them busy.
 *
 * With 2 CPUs PetClinic keeps core 0 and k6, Postgres and the collector all share core 1. A single
 * CPU cannot be split, and [of] refuses it.
 */
data class CpuSplit(
    val petclinic: String,
    val k6: String,
    val support: String,
    val petclinicCores: Int,
) {
    /** The k6 virtual users that keep PetClinic's cores busy in a closed loop: [USERS_PER_CORE] a core. */
    val virtualUsers: Int get() = USERS_PER_CORE * petclinicCores

    companion object {
        /** The most cores PetClinic is pinned to, the 2 the harness has always measured it on. */
        const val MAX_PETCLINIC_CORES = 2

        /** Closed-loop users per PetClinic core; five on two cores held a 2-CPU quota at its limit. */
        const val USERS_PER_CORE = 4

        fun of(hostCpus: Int): CpuSplit {
            require(hostCpus >= 2) { "Pinning needs at least 2 host CPUs to keep PetClinic's cores to itself, the host has $hostCpus" }
            val petclinic = (hostCpus / 4).coerceIn(1, MAX_PETCLINIC_CORES)
            val support = maxOf(1, hostCpus / 4)
            val k6 = hostCpus - petclinic - support
            if (k6 < 1) {
                val rest = range(petclinic, hostCpus - 1)
                return CpuSplit(range(0, petclinic - 1), rest, rest, petclinic)
            }
            return CpuSplit(
                petclinic = range(0, petclinic - 1),
                k6 = range(petclinic, petclinic + k6 - 1),
                support = range(petclinic + k6, hostCpus - 1),
                petclinicCores = petclinic,
            )
        }

        private fun range(
            first: Int,
            last: Int,
        ) = if (first == last) "$first" else "$first-$last"
    }
}
