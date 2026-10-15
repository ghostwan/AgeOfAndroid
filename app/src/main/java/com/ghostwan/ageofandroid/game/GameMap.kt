package com.ghostwan.ageofandroid.game

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GameMap(val width: Int, val height: Int) {
    val terrain = Array(width * height) { Terrain.GRASS }

    /** Id of the building / resource node blocking each tile, -1 if free. */
    val occupant = IntArray(width * height) { -1 }

    /** Small per-tile variation used by the renderer. */
    val shade = ByteArray(width * height)

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun idx(x: Int, y: Int) = y * width + x

    fun terrainAt(x: Int, y: Int) = terrain[idx(x, y)]

    fun passable(x: Int, y: Int): Boolean =
        inBounds(x, y) && terrain[idx(x, y)].walkable && occupant[idx(x, y)] == -1

    fun passable(x: Float, y: Float) = passable(floor(x).toInt(), floor(y).toInt())

    fun canPlace(tx: Int, ty: Int, size: Int): Boolean {
        for (y in ty until ty + size) for (x in tx until tx + size) {
            if (!inBounds(x, y)) return false
            val i = idx(x, y)
            if (!terrain[i].buildable || occupant[i] != -1) return false
        }
        return true
    }

    fun setOccupant(tx: Int, ty: Int, size: Int, id: Int) {
        for (y in ty until ty + size) for (x in tx until tx + size) {
            if (inBounds(x, y)) occupant[idx(x, y)] = id
        }
    }

    // ---------------------------------------------------------------- Pathfinding

    private val gScore = FloatArray(width * height)
    private val cameFrom = IntArray(width * height)
    private val stamp = IntArray(width * height)
    private val closedStamp = IntArray(width * height)
    private var generation = 0

    /**
     * A* on the tile grid (8 directions, no corner cutting).
     * [isGoal] decides whether a tile is acceptable, the heuristic aims at ([gx], [gy]).
     * Returns the list of tiles to walk through (start excluded). If the goal can't be reached the
     * path leads to the closest reachable tile instead.
     */
    fun findPath(
        sx: Int,
        sy: Int,
        gx: Float,
        gy: Float,
        maxNodes: Int = 6000,
        isGoal: (Int, Int) -> Boolean,
    ): List<Pair<Int, Int>> {
        if (!inBounds(sx, sy)) return emptyList()
        if (isGoal(sx, sy)) return emptyList()
        generation++
        val gen = generation
        val open = PriorityQueue<Long>()
        val start = idx(sx, sy)
        gScore[start] = 0f
        cameFrom[start] = -1
        stamp[start] = gen
        open.add(encode(heuristic(sx, sy, gx, gy), start))
        var best = start
        var bestH = heuristic(sx, sy, gx, gy)
        var expanded = 0
        var found = -1
        while (open.isNotEmpty() && expanded < maxNodes) {
            val cur = (open.poll()!! and 0xFFFFFFFFL).toInt()
            if (closedStamp[cur] == gen) continue
            closedStamp[cur] = gen
            expanded++
            val cx = cur % width
            val cy = cur / width
            if (isGoal(cx, cy)) {
                found = cur
                break
            }
            val h = heuristic(cx, cy, gx, gy)
            if (h < bestH) {
                bestH = h
                best = cur
            }
            for (d in DIRS) {
                val nx = cx + d[0]
                val ny = cy + d[1]
                if (!inBounds(nx, ny)) continue
                if (!passable(nx, ny)) continue
                if (d[0] != 0 && d[1] != 0 && (!passable(cx + d[0], cy) || !passable(cx, cy + d[1]))) continue
                val ni = idx(nx, ny)
                if (closedStamp[ni] == gen) continue
                val ng = gScore[cur] + if (d[0] != 0 && d[1] != 0) 1.4142f else 1f
                if (stamp[ni] != gen || ng < gScore[ni]) {
                    stamp[ni] = gen
                    gScore[ni] = ng
                    cameFrom[ni] = cur
                    open.add(encode(ng + heuristic(nx, ny, gx, gy), ni))
                }
            }
        }
        val end = if (found >= 0) found else best
        if (end == start) return emptyList()
        val result = ArrayList<Pair<Int, Int>>()
        var c = end
        while (c != start && c >= 0) {
            result.add(Pair(c % width, c / width))
            c = cameFrom[c]
        }
        result.reverse()
        return smooth(sx + 0.5f, sy + 0.5f, result)
    }

    /** Removes waypoints that can be skipped with a straight walkable line. */
    private fun smooth(sx: Float, sy: Float, path: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (path.size < 3) return path
        val out = ArrayList<Pair<Int, Int>>()
        var fromX = sx
        var fromY = sy
        var i = 0
        while (i < path.size) {
            var j = path.size - 1
            while (j > i && !lineWalkable(fromX, fromY, path[j].first + 0.5f, path[j].second + 0.5f)) j--
            out.add(path[j])
            fromX = path[j].first + 0.5f
            fromY = path[j].second + 0.5f
            i = j + 1
        }
        return out
    }

    fun lineWalkable(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val d = dist(ax, ay, bx, by)
        val steps = max(1, (d / 0.2f).toInt())
        for (s in 1 until steps) {
            val t = s.toFloat() / steps
            val px = ax + (bx - ax) * t
            val py = ay + (by - ay) * t
            // Check a small footprint so units don't scrape corners.
            if (!passable(px, py) || !passable(px + 0.2f, py) || !passable(px - 0.2f, py) ||
                !passable(px, py + 0.2f) || !passable(px, py - 0.2f)
            ) return false
        }
        return true
    }

    private fun heuristic(x: Int, y: Int, gx: Float, gy: Float): Float {
        val dx = abs(x + 0.5f - gx)
        val dy = abs(y + 0.5f - gy)
        return max(dx, dy) + 0.4142f * min(dx, dy)
    }

    private fun encode(f: Float, idx: Int): Long = ((f * 1000).toLong() shl 32) or idx.toLong()

    /** Nearest passable tile to (x, y) by spiral search. */
    fun nearestPassable(x: Int, y: Int, maxRadius: Int = 12): Pair<Int, Int>? {
        if (passable(x, y)) return Pair(x, y)
        for (r in 1..maxRadius) {
            var best: Pair<Int, Int>? = null
            var bestD = Float.MAX_VALUE
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                if (passable(x + dx, y + dy)) {
                    val d = sqrt((dx * dx + dy * dy).toFloat())
                    if (d < bestD) {
                        bestD = d
                        best = Pair(x + dx, y + dy)
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }

    companion object {
        private val DIRS = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
        )
    }
}
