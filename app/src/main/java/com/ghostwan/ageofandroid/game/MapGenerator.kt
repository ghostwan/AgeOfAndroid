package com.ghostwan.ageofandroid.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Random "Arabia-like" map: open land, a few lakes, forests, and fair starting resources. */
class MapGenerator(private val world: World) {
    private val map = world.map
    private val rnd = world.rnd
    private val size = map.width

    fun generate() {
        val slots = world.config.slots
        val starts = startPositions(if (slots.isNotEmpty()) slots.size else world.config.opponents + 1)
        createPlayers(starts)
        paintTerrain(starts)
        starts.forEachIndexed { i, (sx, sy) -> setupBase(i, sx, sy) }
        scatterForests(starts)
        scatterMines(starts)
    }

    private fun startPositions(n: Int): List<Pair<Int, Int>> {
        val m = 11
        val far = size - 1 - m
        val corners = listOf(Pair(m, m), Pair(far, far), Pair(far, m), Pair(m, far))
        return when (n) {
            2 -> if (rnd.nextBoolean()) listOf(corners[0], corners[1]) else listOf(corners[2], corners[3])
            else -> corners.shuffled(rnd).take(n)
        }
    }

    private fun createPlayers(starts: List<Pair<Int, Int>>) {
        val civs = Civ.entries
        val slots = world.config.slots
        if (slots.isNotEmpty()) {
            starts.forEachIndexed { i, (sx, sy) ->
                val s = slots[i]
                world.addPlayer(Player(i, s.name, Player.COLORS[i], s.civ, s.human, sx, sy, s.team))
            }
            return
        }
        starts.forEachIndexed { i, (sx, sy) ->
            val human = i == 0
            val civ = if (human) world.config.civ else civs[rnd.nextInt(civs.size)]
            val name = if (human) "Vous" else "IA ${Player.COLOR_NAMES[i]} (${civ.label})"
            world.addPlayer(Player(i, name, Player.COLORS[i], civ, human, sx, sy))
        }
    }

    private fun paintTerrain(starts: List<Pair<Int, Int>>) {
        for (i in map.shade.indices) map.shade[i] = rnd.nextInt(0, 8).toByte()
        // Dirt patches
        repeat(size * size / 300) {
            blob(rnd.nextInt(size), rnd.nextInt(size), rnd.nextInt(6, 20)) { x, y ->
                map.terrain[map.idx(x, y)] = Terrain.DIRT
            }
        }
        // Dirt around starts
        starts.forEach { (sx, sy) -> disc(sx, sy, 5f) { x, y -> map.terrain[map.idx(x, y)] = Terrain.DIRT } }
        // Lakes away from players
        val lakes = 1 + size / 40
        var placed = 0
        var tries = 0
        while (placed < lakes && tries < 200) {
            tries++
            val cx = rnd.nextInt(6, size - 6)
            val cy = rnd.nextInt(6, size - 6)
            if (starts.any { (sx, sy) -> dist(cx, cy, sx, sy) < 17f }) continue
            val r = rnd.nextInt(3, 6).toFloat()
            disc(cx, cy, r + 1.5f) { x, y -> if (map.terrainAt(x, y) != Terrain.WATER) map.terrain[map.idx(x, y)] = Terrain.SAND }
            disc(cx, cy, r) { x, y -> map.terrain[map.idx(x, y)] = Terrain.WATER }
            // A second lobe for organic shapes.
            val a = rnd.nextDouble() * 2 * PI
            val lx = (cx + cos(a) * r).toInt()
            val ly = (cy + sin(a) * r).toInt()
            disc(lx, ly, r * 0.7f + 1.5f) { x, y -> if (map.terrainAt(x, y) != Terrain.WATER) map.terrain[map.idx(x, y)] = Terrain.SAND }
            disc(lx, ly, r * 0.7f) { x, y -> map.terrain[map.idx(x, y)] = Terrain.WATER }
            placed++
        }
    }

    private fun setupBase(owner: Int, sx: Int, sy: Int) {
        world.addBuilding(owner, BuildingType.TOWN_CENTER, sx - 1, sy - 1, complete = true)
        val toCenter = Math.atan2((size / 2 - sy).toDouble(), (size / 2 - sx).toDouble())
        // 3 villagers + a scout, toward the map centre.
        val (ox, oy) = World.angleOffset(toCenter, 2.8f)
        for (i in 0 until 3) {
            world.spawnUnit(owner, UnitType.VILLAGER, sx + 0.5f + ox + (i - 1) * 0.7f, sy + 0.5f + oy + (i % 2) * 0.5f)
        }
        world.spawnUnit(owner, UnitType.SCOUT, sx + 0.5f + ox * 1.4f, sy + 0.5f + oy * 1.4f - 1f)

        // Resources around the town centre, at angles spread around the base.
        val baseAngle = rnd.nextDouble() * 2 * PI
        val angles = (0 until 5).map { baseAngle + it * 2 * PI / 5 + rnd.nextDouble(-0.25, 0.25) }.shuffled(rnd)
        cluster(NodeKind.BERRIES, sx, sy, angles[0], 5.5f, 6)
        cluster(NodeKind.GOLD, sx, sy, angles[1], 7.5f, 7)
        cluster(NodeKind.STONE, sx, sy, angles[2], 8.5f, 5)
        forest(sx, sy, angles[3], 9f, 40)
        forest(sx, sy, angles[4], 10f, 22)
        cluster(NodeKind.GOLD, sx, sy, angles[4] + 0.6, 13f, 6)
    }

    private fun cluster(kind: NodeKind, sx: Int, sy: Int, angle: Double, d: Float, count: Int) {
        val cx = (sx + cos(angle) * d).toInt().coerceIn(2, size - 3)
        val cy = (sy + sin(angle) * d).toInt().coerceIn(2, size - 3)
        grow(cx, cy, count) { x, y -> world.addNode(kind, x, y) != null }
    }

    private fun forest(sx: Int, sy: Int, angle: Double, d: Float, count: Int) {
        val cx = (sx + cos(angle) * d).toInt().coerceIn(1, size - 2)
        val cy = (sy + sin(angle) * d).toInt().coerceIn(1, size - 2)
        grow(cx, cy, count) { x, y ->
            dist(x, y, sx, sy) > 5.5f && world.addNode(NodeKind.TREE, x, y) != null
        }
    }

    private fun scatterForests(starts: List<Pair<Int, Int>>) {
        val count = size * size / 220
        repeat(count) {
            val cx = rnd.nextInt(size)
            val cy = rnd.nextInt(size)
            if (starts.any { (sx, sy) -> dist(cx, cy, sx, sy) < 12f }) return@repeat
            grow(cx, cy, rnd.nextInt(8, 40)) { x, y -> starts.none { (sx, sy) -> dist(x, y, sx, sy) < 8f } && world.addNode(NodeKind.TREE, x, y) != null }
        }
        // Map border forest
        for (i in 0 until size) {
            for ((x, y) in listOf(Pair(i, 0), Pair(i, size - 1), Pair(0, i), Pair(size - 1, i))) {
                if (rnd.nextFloat() < 0.6f) world.addNode(NodeKind.TREE, x, y)
            }
        }
        // Lone trees
        repeat(size * size / 90) {
            val x = rnd.nextInt(size)
            val y = rnd.nextInt(size)
            if (starts.none { (sx, sy) -> dist(x, y, sx, sy) < 7f }) world.addNode(NodeKind.TREE, x, y)
        }
    }

    private fun scatterMines(starts: List<Pair<Int, Int>>) {
        val extra = size / 16
        repeat(extra * 2) { i ->
            var tries = 0
            while (tries++ < 50) {
                val x = rnd.nextInt(4, size - 4)
                val y = rnd.nextInt(4, size - 4)
                if (starts.any { (sx, sy) -> dist(x, y, sx, sy) < 16f }) continue
                val kind = if (i % 2 == 0) NodeKind.GOLD else NodeKind.STONE
                grow(x, y, if (kind == NodeKind.GOLD) 6 else 4) { tx, ty -> world.addNode(kind, tx, ty) != null }
                if (rnd.nextBoolean()) grow(x + 3, y - 3, 5) { tx, ty -> world.addNode(NodeKind.BERRIES, tx, ty) != null }
                break
            }
        }
    }

    /** Grows a connected clump of [count] tiles from (cx, cy). [place] returns true when a tile was used. */
    private fun grow(cx: Int, cy: Int, count: Int, place: (Int, Int) -> Boolean) {
        val frontier = ArrayList<Pair<Int, Int>>()
        val seen = HashSet<Pair<Int, Int>>()
        frontier += Pair(cx, cy)
        seen += Pair(cx, cy)
        var placed = 0
        var guard = 0
        while (frontier.isNotEmpty() && placed < count && guard++ < count * 20) {
            val idx = rnd.nextInt(frontier.size)
            val (x, y) = frontier.removeAt(idx)
            if (!map.inBounds(x, y)) continue
            if (place(x, y)) {
                placed++
                for ((dx, dy) in NEIGHBOURS) {
                    val n = Pair(x + dx, y + dy)
                    if (seen.add(n)) frontier += n
                }
            }
        }
    }

    private fun blob(cx: Int, cy: Int, count: Int, paint: (Int, Int) -> Unit) {
        var x = cx
        var y = cy
        repeat(count) {
            disc(x, y, 1.6f, paint)
            x += rnd.nextInt(-1, 2)
            y += rnd.nextInt(-1, 2)
        }
    }

    private fun disc(cx: Int, cy: Int, r: Float, paint: (Int, Int) -> Unit) {
        val ri = r.toInt() + 1
        for (y in cy - ri..cy + ri) for (x in cx - ri..cx + ri) {
            if (map.inBounds(x, y) && dist(x, y, cx, cy) <= r) paint(x, y)
        }
    }

    private fun dist(ax: Int, ay: Int, bx: Int, by: Int): Float {
        val dx = (ax - bx).toFloat()
        val dy = (ay - by).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private val NEIGHBOURS = listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))
    }
}
