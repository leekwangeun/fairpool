package kr.fairpool.car.settlement

import kotlin.math.floor

/** One person in the cost game. The driver joins the game too (운전자 포함/제외 is decided when allocating). */
data class Rider(val id: String?, val name: String, val isDriver: Boolean)

data class Share(
    val rider: Rider,
    val amount: Long,
    /** Distance this person is charged for (Shapley value or fixed-route share), in km. */
    val shareKm: Double,
    /** What this person would have paid going alone. */
    val alone: Long
)

data class Allocation(val total: Long, val fuelAmount: Long, val toll: Long, val shares: List<Share>, val method: String)

object Shapley {

    /**
     * 지나가기 규칙: a smaller group can always follow a larger group's route and simply not stop,
     * so its cost is the cheapest route among all groups that contain it.
     */
    fun applyPassingRule(n: Int, raw: DoubleArray): DoubleArray {
        val full = (1 shl n) - 1
        val out = raw.copyOf()
        for (s in 1..full) {
            val rest = full xor s
            var best = raw[s]
            var sub = rest
            while (true) {
                val cost = raw[s or sub]
                if (cost < best) best = cost
                if (sub == 0) break
                sub = (sub - 1) and rest
            }
            out[s] = best
        }
        out[0] = 0.0
        return out
    }

    /** Shapley value per player: average marginal cost over every joining order. cost[mask], cost[0] = 0. */
    fun values(n: Int, cost: DoubleArray): DoubleArray {
        val fact = DoubleArray(n + 1)
        fact[0] = 1.0
        for (i in 1..n) fact[i] = fact[i - 1] * i
        val phi = DoubleArray(n)
        val full = (1 shl n) - 1
        for (i in 0 until n) {
            val bit = 1 shl i
            for (s in 0..full) {
                if (s and bit != 0) continue
                val k = Integer.bitCount(s)
                val weight = fact[k] * fact[n - k - 1] / fact[n]
                phi[i] += weight * (cost[s or bit] - cost[s])
            }
        }
        return phi
    }

    /**
     * 예비 규칙 (고정 경로 섀플리 값): each recorded segment is split equally among the people aboard.
     * [segments] holds (km, aboard bitmask).
     */
    fun fixedRoute(n: Int, segments: List<Pair<Double, Int>>): DoubleArray {
        val share = DoubleArray(n)
        for ((km, aboard) in segments) {
            val count = Integer.bitCount(aboard)
            if (count == 0) continue
            for (i in 0 until n) if (aboard and (1 shl i) != 0) share[i] += km / count
        }
        return share
    }
}

object Settlement {

    fun round10(x: Double): Long = Math.round(x / 10.0) * 10

    /**
     * Splits the real trip cost (actual km × fuel price per km + toll) by each person's share.
     *
     * @param shareKm Shapley value (or fixed-route share) per rider in km; rescaled so it sums to [actualKm]
     * @param aloneKm distance each rider would have driven alone, for the 안전장치 cap and the "아낀 금액" line
     * @param tollPayers riders aboard on the tolled stretch; the toll is split equally among them
     */
    fun allocate(
        riders: List<Rider>,
        shareKm: DoubleArray,
        aloneKm: DoubleArray,
        actualKm: Double,
        fuelPerKm: Double,
        toll: Long,
        tollPayers: Set<Int>,
        includeDriver: Boolean,
        method: String
    ): Allocation {
        val n = riders.size
        val sumKm = shareKm.sum()
        val km = DoubleArray(n) { if (sumKm > 0) shareKm[it] * actualKm / sumKm else actualKm / n }
        val payers = if (tollPayers.isEmpty()) riders.indices.toSet() else tollPayers
        val tollEach = if (toll > 0) toll.toDouble() / payers.size else 0.0

        val pay = DoubleArray(n) { km[it] * fuelPerKm + if (it in payers) tollEach else 0.0 }
        val alone = DoubleArray(n) { aloneKm[it] * fuelPerKm + if (toll > 0 && it in payers) toll.toDouble() else 0.0 }

        val paying = riders.indices.filter { includeDriver || !riders[it].isDriver }.toMutableList()
        if (!includeDriver) {
            // 운전자 제외: the driver's part is spread over the passengers in proportion to their own parts.
            val driverPart = riders.indices.filter { riders[it].isDriver }.sumOf { pay[it] }
            riders.indices.filter { riders[it].isDriver }.forEach { pay[it] = 0.0 }
            val base = paying.sumOf { pay[it] }
            if (base > 0) paying.forEach { pay[it] += driverPart * pay[it] / base }
        }

        // 안전장치: nobody pays more than going alone; the excess moves to the others in proportion.
        val capped = mutableSetOf<Int>()
        repeat(n) {
            val over = paying.filter { it !in capped && pay[it] > alone[it] + 1e-9 }
            if (over.isEmpty()) return@repeat
            var excess = 0.0
            for (i in over) {
                excess += pay[i] - alone[i]
                pay[i] = alone[i]
                capped += i
            }
            val free = paying.filter { it !in capped }
            val base = free.sumOf { pay[it] }
            if (base > 0) free.forEach { pay[it] += excess * pay[it] / base }
        }

        val total = round10(pay.sum())
        val amounts = roundToTotal(pay, total)
        val fuelAmount = round10(actualKm * fuelPerKm)
        val shares = riders.indices.map { Share(riders[it], amounts[it], km[it], round10(alone[it])) }
        return Allocation(total, fuelAmount, toll, shares, method)
    }

    /** Rounds every amount to 10원 while keeping the sum exactly [total] (largest remainder first). */
    fun roundToTotal(values: DoubleArray, total: Long): LongArray {
        val floors = LongArray(values.size) { (floor(values[it] / 10.0) * 10).toLong() }
        var left = (total - floors.sum()) / 10
        val order = values.indices.sortedByDescending { values[it] - floors[it] }
        var k = 0
        while (left > 0 && order.isNotEmpty()) {
            floors[order[k % order.size]] += 10
            left--
            k++
        }
        return floors
    }
}
