package com.ghostwan.ageofandroid.game

class Player(
    val id: Int,
    val name: String,
    val color: Long,
    val civ: Civ,
    /** Var: when a guest leaves a multiplayer game, an AI takes over their seat. */
    var isHuman: Boolean,
    startX: Int,
    startY: Int,
    /** Players of the same team are allies: shared vision, no fighting. */
    val team: Int = id,
) {
    val res = Resources(food = 200, wood = 200, gold = 100, stone = 200)
    var age = Age.DARK
    val techs = HashSet<Tech>()
    var defeated = false

    /** Start location (centre of the initial town centre). */
    val startX = startX
    val startY = startY

    // Cached each tick by the world.
    var pop = 0
    var popCap = 0
    var researchingAge = false
    val researching = HashSet<Tech>()

    // Stats
    var unitsKilled = 0
    var unitsLost = 0
    var buildingsRazed = 0
    val gathered = FloatArray(ResourceType.entries.size)

    fun has(tech: Tech) = tech in techs

    fun buildingCost(type: BuildingType): Cost =
        if (civ == Civ.JAPANESE && type in setOf(BuildingType.MILL, BuildingType.LUMBER_CAMP, BuildingType.MINING_CAMP)) {
            type.cost.scaled(0.5f)
        } else {
            type.cost
        }

    fun carryCapacity() = if (has(Tech.WHEELBARROW)) 15f else 10f

    companion object {
        const val MAX_POP = 200
        val COLORS = longArrayOf(0xFF2F6FE0, 0xFFD8312B, 0xFFE9C21B, 0xFF2EAA4A)
        val COLOR_NAMES = listOf("Bleu", "Rouge", "Jaune", "Vert")
    }
}
