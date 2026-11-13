package kr.fairpool.car.settlement

import kotlin.math.floor

/** One person in the cost game. The driver joins the game too (운전자 포함/제외 is decided when allocating). */
data class Rider(val id: String?, val name: String, val isDriver: Boolean)

data class Share(
    val rider: Rider,
    val amount: Long,
    /** Distance this person is charged for (Shapley value, km ridden or fixed-route share), in km. */
    val shareKm: Double,
    /** What this person would have paid going alone. */
    val alone: Long
)

data class Allocation(
    val total: Long,
    val fuelAmount: Long,
    val toll: Long,
    val shares: List<Share>,
    val method: String,
    /** Indexes into [shares] of the people held at their going-alone amount (안전장치). */
    val capped: Set<Int> = emptySet(),
    /** Part of the total nobody else could take after the cap: the driver covers it (운전자 부담). */
    val driverAbsorbed: Long = 0,
    /** Amounts of the 공정 정산 (Shapley, or the fixed-route share without routing) for the same people, when another rule was used. */
    val fair: List<Long>? = null,
    /** The group distances were not routed after arrival, so going-alone amounts are estimates (임시). */
    val provisional: Boolean = false
) {
    /** How much more (plus) or less (minus) person [i] pays than under 공정 정산. */
    fun diffFromFair(i: Int): Long? = fair?.getOrNull(i)?.let { shares[i].amount - it }
}

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

    /** 탄 거리: every km each person was aboard. The driver (bit 0) is aboard on every segment, so gets the driven km. */
    fun riddenKm(n: Int, segments: List<Pair<Double, Int>>): DoubleArray {
        val km = DoubleArray(n)
        for ((len, aboard) in segments) for (i in 0 until n) if (aboard and (1 shl i) != 0) km[i] += len
        return km
    }
}

object Settlement {

    fun round10(x: Double): Long = Math.round(x / 10.0) * 10

    fun floor10(x: Double): Long = (floor(x / 10.0) * 10).toLong()

    /**
     * Splits the real trip cost (actual km × fuel price per km + toll) by each person's share.
     *
     * The total always equals round10(fuel + toll). Whatever the going-alone cap leaves that nobody can take
     * is returned as [Allocation.driverAbsorbed].
     *
     * @param shareKm Shapley value, km ridden or fixed-route share per rider; rescaled so it sums to [actualKm]
     * @param aloneKm distance each rider would have driven alone, for the 안전장치 cap and the "혼자면" line
     * @param tollPayers riders aboard on the tolled stretch; the toll is split equally among them
     * @param capAtAlone false when nobody's going-alone cost is known for sure (N분의 1, no routing)
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
        method: String,
        capAtAlone: Boolean = true
    ): Allocation {
        val n = riders.size
        val sumKm = shareKm.sum()
        val km = DoubleArray(n) { if (sumKm > 0) shareKm[it] * actualKm / sumKm else actualKm / n }
        val payers = if (tollPayers.isEmpty()) riders.indices.toSet() else tollPayers
        val tollEach = if (toll > 0) toll.toDouble() / payers.size else 0.0

        val fuel = DoubleArray(n) { km[it] * fuelPerKm }
        val tollPart = DoubleArray(n) { if (it in payers) tollEach else 0.0 }
        val alone = DoubleArray(n) { aloneKm[it] * fuelPerKm + if (toll > 0 && it in payers) toll.toDouble() else 0.0 }

        val paying = riders.indices.filter { includeDriver || !riders[it].isDriver }
        var remainder = 0.0
        if (!includeDriver) {
            // 운전자 제외: the driver's fuel goes to the passengers in proportion to their own fuel,
            // the driver's toll only to the other people who were aboard on the tolled stretch.
            for (d in riders.indices.filter { riders[it].isDriver }) {
                val driverFuel = fuel[d]
                val driverToll = tollPart[d]
                fuel[d] = 0.0
                tollPart[d] = 0.0
                if (paying.isEmpty()) {
                    remainder += driverFuel + driverToll
                    continue
                }
                val fuelBase = paying.sumOf { fuel[it] }
                val ratio = DoubleArray(n) { i ->
                    when {
                        i !in paying -> 0.0
                        fuelBase > 0 -> fuel[i] / fuelBase
                        else -> 1.0 / paying.size
                    }
                }
                // when nobody else rode the tolled stretch, the toll follows the fuel ratio
                val tollTakers = paying.filter { it in payers }
                for (i in paying) {
                    fuel[i] += driverFuel * ratio[i]
                    tollPart[i] += if (tollTakers.isNotEmpty()) {
                        if (i in tollTakers) driverToll / tollTakers.size else 0.0
                    } else driverToll * ratio[i]
                }
            }
        }
        val pay = DoubleArray(n) { fuel[it] + tollPart[it] }

        // 안전장치: nobody pays more than going alone; the excess moves to the others in proportion.
        val capped = mutableSetOf<Int>()
        if (capAtAlone) {
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
                if (base > 0) free.forEach { pay[it] += excess * pay[it] / base } else remainder += excess
            }
        }

        // the total is the real cost; capped people never get the extra 10원 of rounding
        val total = round10(actualKm * fuelPerKm + toll)
        val amounts = LongArray(n)
        for (i in capped) amounts[i] = floor10(pay[i])
        val free = paying.filter { it !in capped }
        if (free.isNotEmpty()) {
            val freeTotal = total - capped.sumOf { amounts[it] } - round10(remainder)
            val rounded = roundToTotal(DoubleArray(free.size) { pay[free[it]] }, freeTotal)
            free.forEachIndexed { k, i -> amounts[i] = rounded[k] }
        }
        val absorbed = (total - amounts.sum()).coerceAtLeast(0)

        val fuelAmount = round10(actualKm * fuelPerKm)
        val shares = riders.indices.map { Share(riders[it], amounts[it], km[it], round10(alone[it])) }
        return Allocation(total, fuelAmount, toll, shares, method, capped.toSet(), absorbed)
    }

    /**
     * Rounds every amount to 10원 while keeping the sum exactly [total] (largest remainder first). When the floors
     * already add up to more than [total], 10원 comes off the smallest remainders first (never below 0).
     */
    fun roundToTotal(values: DoubleArray, total: Long): LongArray {
        val floors = LongArray(values.size) { floor10(values[it]) }
        var left = (total - floors.sum()) / 10
        val order = values.indices.sortedByDescending { values[it] - floors[it] }
        var k = 0
        while (left > 0 && order.isNotEmpty()) {
            floors[order[k % order.size]] += 10
            left--
            k++
        }
        val back = order.reversed().filter { floors[it] >= 10 }
        k = 0
        while (left < 0 && back.isNotEmpty() && k < back.size * 1000) {
            val i = back[k % back.size]
            if (floors[i] >= 10) {
                floors[i] -= 10
                left++
            }
            k++
        }
        return floors
    }
}
