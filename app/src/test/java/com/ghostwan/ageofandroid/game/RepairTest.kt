package com.ghostwan.ageofandroid.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairTest {
    private fun setup(): Triple<World, Building, GameUnit> {
        val w = World(GameConfig(opponents = 1, mapSize = 56, seed = 5L))
        w.ais.clear()
        val tc = w.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        val v = w.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        return Triple(w, tc, v)
    }

    @Test
    fun villagersRepairForHalfTheCost() {
        val (w, tc, v) = setup()
        tc.hp = tc.maxHp - 600f
        assertTrue(w.needsRepair(tc))
        val wood = w.human.res.exact(ResourceType.WOOD)
        val stone = w.human.res.exact(ResourceType.STONE)
        w.commandSmart(listOf(v), tc.x, tc.y, tc)
        assertEquals(Task.BUILD, v.task)
        w.forceAdvance(120f)
        assertFalse(w.needsRepair(tc))
        assertEquals(tc.maxHp, tc.hp, 0.01f)
        // 600 HP of a 2400 HP town centre (275 wood, 100 stone): 1/4 of half the price.
        assertEquals(wood - 275f * 0.5f * 0.25f, w.human.res.exact(ResourceType.WOOD), 0.5f)
        assertEquals(stone - 100f * 0.5f * 0.25f, w.human.res.exact(ResourceType.STONE), 0.5f)
        assertEquals(Task.IDLE, v.task)
    }

    @Test
    fun repairStopsWithoutResources() {
        val (w, tc, v) = setup()
        tc.hp = 500f
        ResourceType.entries.forEach { w.human.res.setExact(it, 0f) }
        w.commandSmart(listOf(v), tc.x, tc.y, tc)
        w.forceAdvance(30f)
        assertEquals(500f, tc.hp, 0.01f)
        assertEquals(Task.IDLE, v.task)
        assertTrue(w.events.any { it.text.startsWith("Ressources insuffisantes pour réparer") })
    }
}
