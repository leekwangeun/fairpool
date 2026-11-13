package kr.fairpool.car.settlement

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SettlementTest {

    // 기획서 3.4 계산 예시 1: 0 지훈(운전자), 1 민지, 2 서연, 3 태호. Raw OSRM distances in km.
    private fun example1Raw(): DoubleArray {
        val d = DoubleArray(16)
        fun m(vararg p: Int) = p.fold(0) { acc, i -> acc or (1 shl i) }
        d[m(0)] = 69.5; d[m(1)] = 72.3; d[m(2)] = 71.6; d[m(3)] = 2.0
        d[m(0, 1)] = 78.6; d[m(0, 2)] = 77.1; d[m(0, 3)] = 76.4
        d[m(1, 2)] = 74.2; d[m(1, 3)] = 76.1; d[m(2, 3)] = 72.4
        d[m(0, 1, 2)] = 79.7; d[m(0, 1, 3)] = 80.9; d[m(0, 2, 3)] = 77.2; d[m(1, 2, 3)] = 75.2
        d[m(0, 1, 2, 3)] = 80.0
        return d
    }

    private val example1Riders = listOf(
        Rider(null, "지훈", true), Rider("p1", "민지", false), Rider("p2", "서연", false), Rider("p3", "태호", false)
    )

    private fun example1(includeDriver: Boolean): Allocation {
        val cost = Shapley.applyPassingRule(4, example1Raw())
        val phi = Shapley.values(4, cost)
        val alone = DoubleArray(4) { cost[1 shl it] }
        return Settlement.allocate(
            example1Riders, phi, alone, actualKm = 80.0, fuelPerKm = 1700.0 / 11.0,
            toll = 3000, tollPayers = setOf(0, 1, 2), includeDriver = includeDriver, method = "shapley"
        )
    }

    @Test
    fun passingRuleUsesTheShorterSupersetRoute() {
        val cost = Shapley.applyPassingRule(4, example1Raw())
        assertEquals(75.2, cost[0b1010], 1e-9) // 민지, 태호: 76.1 → 75.2 via 민지, 서연, 태호
        assertEquals(80.0, cost[0b1011], 1e-9) // 지훈, 민지, 태호: 80.9 → 80.0 via everyone
    }

    @Test
    fun example1ShapleyKmMatchesThePlan() {
        val phi = Shapley.values(4, Shapley.applyPassingRule(4, example1Raw()))
        assertEquals(80.0, phi.sum(), 1e-9)
        assertEquals(27.0, phi[0], 0.05)
        assertEquals(26.6, phi[1], 0.05)
        assertEquals(24.7, phi[2], 0.05)
        assertEquals(1.7, phi[3], 0.05)
    }

    @Test
    fun example1DriverIncluded() {
        val a = example1(includeDriver = true)
        assertEquals(15360, a.total)
        assertArrayEquals(longArrayOf(5170, 5110, 4820, 260), a.shares.map { it.amount }.toLongArray())
        assertArrayEquals(longArrayOf(13740, 14170, 14070, 310), a.shares.map { it.alone }.toLongArray())
    }

    @Test
    fun example1DriverIncludedHasNoCapNoRemainder() {
        val a = example1(includeDriver = true)
        assertEquals(emptySet<Int>(), a.capped)
        assertEquals(0L, a.driverAbsorbed)
    }

    @Test
    fun example1DriverExcludedCapsTaehoAtGoingAlone() {
        val a = example1(includeDriver = false)
        assertEquals(15360, a.total)
        // 태호 is held at floor10(309.1원) = 300원; the 10원 of rounding goes to someone who is not capped
        assertArrayEquals(longArrayOf(0, 7750, 7310, 300), a.shares.map { it.amount }.toLongArray())
        assertEquals(setOf(3), a.capped)
        assertEquals(0L, a.driverAbsorbed)
        assertEquals(a.total, a.shares.sumOf { it.amount })
    }

    // ---- 운전자 제외: the driver's toll goes only to the other people aboard on the tolled stretch --------------

    @Test
    fun driverExcludedTollGoesOnlyToOtherTollPayers() {
        // 0 운전자, 1 A (aboard on the tolled stretch), 2 B (got in after it); 10km each, 100원/km, toll 2,000원
        val a = Settlement.allocate(
            listOf(Rider(null, "운전자", true), Rider("a", "A", false), Rider("b", "B", false)),
            doubleArrayOf(10.0, 10.0, 10.0), doubleArrayOf(100.0, 100.0, 100.0), actualKm = 30.0, fuelPerKm = 100.0,
            toll = 2000, tollPayers = setOf(0, 1), includeDriver = false, method = "shapley"
        )
        assertEquals(5000, a.total)
        // fuel: 1,000 each, the driver's 1,000 split 500/500; toll: A pays its own 1,000 and the driver's 1,000
        assertArrayEquals(longArrayOf(0, 3500, 1500), a.shares.map { it.amount }.toLongArray())
    }

    @Test
    fun driverExcludedTollFollowsFuelWhenNobodyElseRodeIt() {
        val a = Settlement.allocate(
            listOf(Rider(null, "운전자", true), Rider("a", "A", false), Rider("b", "B", false)),
            doubleArrayOf(10.0, 10.0, 10.0), doubleArrayOf(100.0, 100.0, 100.0), actualKm = 30.0, fuelPerKm = 100.0,
            toll = 2000, tollPayers = setOf(0), includeDriver = false, method = "shapley"
        )
        assertEquals(5000, a.total)
        assertArrayEquals(longArrayOf(0, 2500, 2500), a.shares.map { it.amount }.toLongArray())
    }

    // ---- cap and rounding keep the total ------------------------------------------------------------------

    @Test
    fun capRemainderNobodyCanTakeBecomesDriverAbsorbed() {
        // both passengers would pay 3,000원 but going alone costs 1,055원 and 1,000원
        val a = Settlement.allocate(
            listOf(Rider(null, "운전자", true), Rider("a", "A", false), Rider("b", "B", false)),
            doubleArrayOf(20.0, 20.0, 20.0), doubleArrayOf(60.0, 10.55, 10.0), actualKm = 60.0, fuelPerKm = 100.0,
            toll = 0, tollPayers = emptySet(), includeDriver = false, method = "shapley"
        )
        assertEquals(6000, a.total)
        assertArrayEquals(longArrayOf(0, 1050, 1000), a.shares.map { it.amount }.toLongArray())
        assertEquals(setOf(1, 2), a.capped)
        assertEquals(3950L, a.driverAbsorbed)
        assertEquals(a.total, a.shares.sumOf { it.amount } + a.driverAbsorbed)
    }

    @Test
    fun cappedPersonNeverGetsTheRoundingTen() {
        // A is capped at 1,009원 going alone: pays 1,000원, never 1,010원
        val a = Settlement.allocate(
            listOf(Rider(null, "운전자", true), Rider("a", "A", false)),
            doubleArrayOf(10.0, 20.0), doubleArrayOf(50.0, 10.09), actualKm = 30.0, fuelPerKm = 100.0,
            toll = 0, tollPayers = emptySet(), includeDriver = true, method = "shapley"
        )
        assertEquals(3000, a.total)
        assertEquals(1000L, a.shares[1].amount)
        assertEquals(2000L, a.shares[0].amount)
        assertEquals(0L, a.driverAbsorbed)
    }

    @Test
    fun roundingCanTakeTenOffWhenFloorsOvershoot() {
        val r = Settlement.roundToTotal(doubleArrayOf(1001.0, 1009.0), 1990)
        assertEquals(1990L, r.sum())
        assertArrayEquals(longArrayOf(990, 1000), r)
    }

    // ---- 탄 거리 비례 and N분의 1 ----------------------------------------------------------------------------

    private val trio = listOf(Rider(null, "운전자", true), Rider("a", "A", false), Rider("b", "B", false))

    // driver + A 10km, everyone 20km, driver alone 10km
    private val trioSegments = listOf(10.0 to 0b011, 20.0 to 0b111, 10.0 to 0b001)

    @Test
    fun riddenKmCountsEveryKmAboard() {
        assertArrayEquals(doubleArrayOf(40.0, 30.0, 20.0), Shapley.riddenKm(3, trioSegments), 1e-9)
    }

    @Test
    fun distanceRuleDriverIncludedSplitsByKmRidden() {
        val a = Settlement.allocate(trio, Shapley.riddenKm(3, trioSegments), doubleArrayOf(99.0, 99.0, 99.0),
            actualKm = 40.0, fuelPerKm = 100.0, toll = 0, tollPayers = emptySet(), includeDriver = true, method = "distance")
        assertEquals(4000, a.total)
        // 40 : 30 : 20 of 4,000원
        assertArrayEquals(longArrayOf(1780, 1330, 890), a.shares.map { it.amount }.toLongArray())
    }

    @Test
    fun distanceRuleDriverExcludedSplitsAmongPassengersByKmRidden() {
        val a = Settlement.allocate(trio, Shapley.riddenKm(3, trioSegments), doubleArrayOf(99.0, 99.0, 99.0),
            actualKm = 40.0, fuelPerKm = 100.0, toll = 0, tollPayers = emptySet(), includeDriver = false, method = "distance")
        assertEquals(4000, a.total)
        assertArrayEquals(longArrayOf(0, 2400, 1600), a.shares.map { it.amount }.toLongArray())
    }

    @Test
    fun equalRuleWithAndWithoutDriver() {
        val with = Settlement.allocate(trio, DoubleArray(3) { 1.0 }, DoubleArray(3) { 1.0 }, actualKm = 30.0,
            fuelPerKm = 100.0, toll = 0, tollPayers = setOf(0, 1, 2), includeDriver = true, method = "equal", capAtAlone = false)
        assertArrayEquals(longArrayOf(1000, 1000, 1000), with.shares.map { it.amount }.toLongArray())
        val without = Settlement.allocate(trio, DoubleArray(3) { 1.0 }, DoubleArray(3) { 1.0 }, actualKm = 30.0,
            fuelPerKm = 100.0, toll = 600, tollPayers = setOf(0, 1, 2), includeDriver = false, method = "equal", capAtAlone = false)
        assertEquals(3600, without.total)
        assertArrayEquals(longArrayOf(0, 1800, 1800), without.shares.map { it.amount }.toLongArray())
        assertEquals(emptySet<Int>(), without.capped)
    }

    @Test
    fun differenceFromFairSettlement() {
        val distance = Settlement.allocate(trio, Shapley.riddenKm(3, trioSegments), doubleArrayOf(99.0, 99.0, 99.0),
            actualKm = 40.0, fuelPerKm = 100.0, toll = 0, tollPayers = emptySet(), includeDriver = true, method = "distance")
        val fair = Settlement.allocate(trio, doubleArrayOf(20.0, 12.0, 8.0), doubleArrayOf(99.0, 99.0, 99.0),
            actualKm = 40.0, fuelPerKm = 100.0, toll = 0, tollPayers = emptySet(), includeDriver = true, method = "shapley")
        val a = distance.copy(fair = fair.shares.map { it.amount })
        assertEquals(listOf(2000L, 1200L, 800L), a.fair)
        assertEquals(-220L, a.diffFromFair(0))
        assertEquals(130L, a.diffFromFair(1))
        assertEquals(90L, a.diffFromFair(2))
        assertEquals(null, distance.diffFromFair(0))
    }

    @Test
    fun example2PickupOnTheWay() {
        // 0 운전자, 1 수진: alone 40km / 34km, together 46km; 14km/L at 1,700원/L, no toll.
        val cost = doubleArrayOf(0.0, 40.0, 34.0, 46.0)
        val phi = Shapley.values(2, cost)
        assertEquals(26.0, phi[0], 1e-9)
        assertEquals(20.0, phi[1], 1e-9)
        val a = Settlement.allocate(
            listOf(Rider(null, "운전자", true), Rider("p1", "수진", false)), phi,
            doubleArrayOf(40.0, 34.0), actualKm = 46.0, fuelPerKm = 1700.0 / 14.0,
            toll = 0, tollPayers = emptySet(), includeDriver = true, method = "shapley"
        )
        assertEquals(5590, a.total)
        assertArrayEquals(longArrayOf(3160, 2430), a.shares.map { it.amount }.toLongArray())
    }

    @Test
    fun fixedRouteSplitsEachSegmentAmongThoseAboard() {
        // 10km with driver + A, then 4km with driver only.
        val share = Shapley.fixedRoute(2, listOf(10.0 to 0b11, 4.0 to 0b01))
        assertArrayEquals(doubleArrayOf(9.0, 5.0), share, 1e-9)
    }

    @Test
    fun roundingKeepsTheTotal() {
        val r = Settlement.roundToTotal(doubleArrayOf(5173.2, 5110.9, 4817.3, 262.7), 15360)
        assertArrayEquals(longArrayOf(5170, 5110, 4820, 260), r)
    }
}
