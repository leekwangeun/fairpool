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
    fun example1DriverExcludedCapsTaehoAtGoingAlone() {
        val a = example1(includeDriver = false)
        assertEquals(15360, a.total)
        assertArrayEquals(longArrayOf(0, 7750, 7300, 310), a.shares.map { it.amount }.toLongArray())
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
