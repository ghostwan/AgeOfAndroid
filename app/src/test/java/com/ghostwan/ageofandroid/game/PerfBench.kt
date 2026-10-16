package com.ghostwan.ageofandroid.game

import org.junit.Ignore
import org.junit.Test

/**
 * Manual benchmark: a 4-player hard game where an AI also plays the human side, printing the
 * average and worst engine tick per game minute. Remove @Ignore to run it.
 */
@Ignore("manual benchmark")
class PerfBench {
    @Test
    fun bench() {
        val w = World(GameConfig(civ = Civ.FRANKS, opponents = 3, difficulty = Difficulty.HARD, mapSize = 96, seed = 3L))
        w.ais += AiPlayer(w, w.human, Difficulty.HARD)
        val dt = 1f / 20f
        for (minute in 1..40) {
            var total = 0L
            var worst = 0L
            repeat(60 * 20) {
                val t0 = System.nanoTime()
                w.update(dt)
                val d = System.nanoTime() - t0
                total += d
                if (d > worst) worst = d
            }
            println(
                "min $minute: avg ${"%.2f".format(total / 1200 / 1e6)} ms, worst ${"%.1f".format(worst / 1e6)} ms, " +
                    "units=${w.units.size} buildings=${w.buildings.size} winner=${w.winner}",
            )
            if (w.winner != null) break
        }
    }
}
