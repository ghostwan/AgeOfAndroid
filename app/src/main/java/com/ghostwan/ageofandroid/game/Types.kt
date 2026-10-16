package com.ghostwan.ageofandroid.game

enum class ResourceType(val label: String, val icon: String) {
    FOOD("Nourriture", "🍖"),
    WOOD("Bois", "🌲"),
    GOLD("Or", "💰"),
    STONE("Pierre", "⛰️"),
}

data class Cost(val food: Int = 0, val wood: Int = 0, val gold: Int = 0, val stone: Int = 0) {
    operator fun get(type: ResourceType): Int = when (type) {
        ResourceType.FOOD -> food
        ResourceType.WOOD -> wood
        ResourceType.GOLD -> gold
        ResourceType.STONE -> stone
    }

    fun scaled(factor: Float) = Cost(
        (food * factor).toInt(), (wood * factor).toInt(), (gold * factor).toInt(), (stone * factor).toInt(),
    )

    val isFree get() = food == 0 && wood == 0 && gold == 0 && stone == 0

    override fun toString(): String = ResourceType.entries
        .filter { this[it] > 0 }
        .joinToString(" ") { "${it.icon}${this[it]}" }
}

class Resources(food: Int = 0, wood: Int = 0, gold: Int = 0, stone: Int = 0) {
    private val amounts = floatArrayOf(food.toFloat(), wood.toFloat(), gold.toFloat(), stone.toFloat())

    operator fun get(type: ResourceType): Int = amounts[type.ordinal].toInt()

    /** Exact amount, including the fractions gathered so far (used by saves). */
    fun exact(type: ResourceType): Float = amounts[type.ordinal]

    fun setExact(type: ResourceType, amount: Float) {
        amounts[type.ordinal] = amount
    }

    fun add(type: ResourceType, amount: Float) {
        amounts[type.ordinal] += amount
    }

    fun canAfford(cost: Cost) = ResourceType.entries.all { this[it] >= cost[it] }

    fun pay(cost: Cost): Boolean {
        if (!canAfford(cost)) return false
        ResourceType.entries.forEach { amounts[it.ordinal] -= cost[it] }
        return true
    }

    fun refund(cost: Cost) {
        ResourceType.entries.forEach { amounts[it.ordinal] += cost[it] }
    }
}

enum class Age(val label: String, val cost: Cost, val researchTime: Float) {
    DARK("Âge sombre", Cost(), 0f),
    FEUDAL("Âge féodal", Cost(food = 500), 60f),
    CASTLE("Âge des châteaux", Cost(food = 800, gold = 200), 80f),
    IMPERIAL("Âge impérial", Cost(food = 1000, gold = 800), 100f);

    val next: Age? get() = entries.getOrNull(ordinal + 1)
}

enum class Civ(val label: String, val bonus: String) {
    FRANKS("Francs", "Cavalerie +20% PV"),
    BRITONS("Bretons", "Archers et tours +1 portée"),
    TEUTONS("Teutons", "Infanterie +1 armure de mêlée"),
    JAPANESE("Japonais", "Camps de ressources -50% de coût"),
}

enum class Difficulty(val label: String, val gatherBonus: Float, val firstAttack: Float, val villagerTarget: Int) {
    EASY("Facile", 0.8f, 600f, 16),
    NORMAL("Normal", 1.0f, 420f, 24),
    HARD("Difficile", 1.25f, 300f, 32),
}

enum class Category(val label: String) {
    VILLAGER("villageois"), INFANTRY("infanterie"), ARCHER("archers"), CAVALRY("cavalerie"), SIEGE("siège"), BUILDING("bâtiments"),
}

enum class BuildingType(
    val label: String,
    val icon: String,
    val cost: Cost,
    val buildTime: Float,
    val maxHp: Int,
    val size: Int,
    val popProvided: Int = 0,
    val minAge: Age = Age.DARK,
    val dropSite: Set<ResourceType> = emptySet(),
    val attack: Int = 0,
    val range: Float = 0f,
    val los: Int = 4,
    val military: Boolean = false,
    val walkable: Boolean = false,
    val description: String = "",
) {
    TOWN_CENTER(
        "Forum", "🏛️", Cost(wood = 275, stone = 100), 120f, 2400, 3, popProvided = 5,
        dropSite = ResourceType.entries.toSet(), attack = 5, range = 6f, los = 8,
        description = "Forme les villageois, dépôt de toutes les ressources, permet de changer d'âge.",
    ),
    HOUSE("Maison", "🏠", Cost(wood = 25), 12f, 550, 2, popProvided = 5, description = "+5 de population maximale : sans maisons, vous ne pouvez plus former d'unités."),
    MILL("Moulin", "🌾", Cost(wood = 100), 20f, 600, 2, dropSite = setOf(ResourceType.FOOD), description = "Dépôt de nourriture : placez-le près des baies et des fermes pour raccourcir les trajets des villageois."),
    LUMBER_CAMP("Camp de bûcherons", "🌲", Cost(wood = 100), 20f, 600, 2, dropSite = setOf(ResourceType.WOOD), description = "Dépôt de bois : placez-le au bord d'une forêt pour raccourcir les trajets des bûcherons."),
    MINING_CAMP(
        "Camp de mineurs", "⛏️", Cost(wood = 100), 20f, 600, 2,
        dropSite = setOf(ResourceType.GOLD, ResourceType.STONE), description = "Dépôt d'or et de pierre : placez-le à côté d'une mine pour raccourcir les trajets des mineurs.",
    ),
    FARM("Ferme", "🌱", Cost(wood = 60), 10f, 480, 2, walkable = true, description = "Nourriture renouvelable, récoltée par un villageois. Se ressème automatiquement contre du bois une fois épuisée."),
    BARRACKS("Caserne", "⚔️", Cost(wood = 175), 30f, 1200, 3, military = true, description = "Forme l'infanterie : miliciens efficaces contre les bâtiments, piquiers contre la cavalerie."),
    ARCHERY_RANGE("Camp de tir", "🏹", Cost(wood = 175), 30f, 1200, 3, minAge = Age.FEUDAL, military = true, description = "Forme les archers : attaque à distance, efficaces contre l'infanterie."),
    STABLE("Écurie", "🐎", Cost(wood = 175), 30f, 1200, 3, minAge = Age.FEUDAL, military = true, description = "Forme la cavalerie : éclaireurs rapides pour explorer, puis chevaliers puissants."),
    BLACKSMITH("Forge", "⚒️", Cost(wood = 150), 30f, 1500, 2, minAge = Age.FEUDAL, description = "Recherche des améliorations d'attaque et d'armure pour vos soldats."),
    WATCH_TOWER(
        "Tour de guet", "🗼", Cost(wood = 25, stone = 125), 40f, 1000, 1, minAge = Age.FEUDAL,
        attack = 5, range = 7f, los = 9, description = "Défense : tire des flèches sur les ennemis à portée et révèle les alentours.",
    ),
    SIEGE_WORKSHOP("Atelier de siège", "🛠️", Cost(wood = 200), 35f, 1500, 3, minAge = Age.CASTLE, military = true, description = "Forme les béliers : lents mais dévastateurs contre les bâtiments."),
    WONDER(
        "Merveille", "✨", Cost(wood = 1000, gold = 1000, stone = 1000), 180f, 4800, 4, minAge = Age.IMPERIAL,
        description = "Gardez-la debout 3 minutes pour gagner !",
    );

    val isDropSite get() = dropSite.isNotEmpty()
    val trains: List<UnitType> by lazy { UnitType.entries.filter { it.trainedAt == this } }
    val techs: List<Tech> by lazy { Tech.entries.filter { it.researchedAt == this } }
}

enum class UnitType(
    val label: String,
    val icon: String,
    val category: Category,
    val cost: Cost,
    val trainTime: Float,
    val maxHp: Int,
    val attack: Int,
    val meleeArmor: Int,
    val pierceArmor: Int,
    val range: Float,
    val reload: Float,
    val speed: Float,
    val los: Int,
    val trainedAt: BuildingType,
    val minAge: Age = Age.DARK,
    val bonus: Map<Category, Int> = emptyMap(),
    val description: String = "",
) {
    VILLAGER(
        "Villageois", "👷", Category.VILLAGER, Cost(food = 50), 15f, 25, 3, 0, 1, 0f, 2f, 1.15f, 4,
        BuildingType.TOWN_CENTER,
        description = "Récolte les ressources et construit les bâtiments. Se défend mal au combat.",
    ),
    MILITIA(
        "Milicien", "🗡️", Category.INFANTRY, Cost(food = 60, gold = 20), 15f, 45, 5, 0, 1, 0f, 2f, 1.0f, 4,
        BuildingType.BARRACKS, bonus = mapOf(Category.BUILDING to 3),
        description = "Fantassin polyvalent et bon marché, efficace pour raser les bâtiments.",
    ),
    SPEARMAN(
        "Piquier", "🔱", Category.INFANTRY, Cost(food = 35, wood = 25), 15f, 45, 3, 0, 0, 0f, 2f, 1.0f, 4,
        BuildingType.BARRACKS, Age.FEUDAL, mapOf(Category.CAVALRY to 15, Category.BUILDING to 1),
        description = "Fantassin anti-cavalerie, idéal contre éclaireurs et chevaliers. Vulnérable aux archers.",
    ),
    ARCHER(
        "Archer", "🏹", Category.ARCHER, Cost(wood = 25, gold = 45), 18f, 30, 4, 0, 0, 4f, 2f, 0.96f, 6,
        BuildingType.ARCHERY_RANGE, Age.FEUDAL, mapOf(Category.INFANTRY to 3),
        description = "Attaque à distance, efficace contre l'infanterie. Fragile au corps à corps, surtout face à la cavalerie.",
    ),
    SCOUT(
        "Éclaireur", "🐴", Category.CAVALRY, Cost(food = 80), 15f, 45, 3, 0, 2, 0f, 2f, 1.75f, 6,
        BuildingType.STABLE, Age.FEUDAL, mapOf(Category.ARCHER to 4),
        description = "Cavalier très rapide, parfait pour explorer et chasser les archers. Faible contre les piquiers.",
    ),
    KNIGHT(
        "Chevalier", "🏇", Category.CAVALRY, Cost(food = 60, gold = 75), 20f, 100, 10, 2, 2, 0f, 1.8f, 1.5f, 5,
        BuildingType.STABLE, Age.CASTLE,
        description = "Cavalerie lourde, puissante et résistante. Vulnérable aux piquiers.",
    ),
    RAM(
        "Bélier", "🐏", Category.SIEGE, Cost(wood = 160, gold = 75), 25f, 175, 2, 0, 180, 0f, 3f, 0.6f, 3,
        BuildingType.SIEGE_WORKSHOP, Age.CASTLE, mapOf(Category.BUILDING to 125),
        description = "Engin de siège lent : dévastateur contre les bâtiments, quasi insensible aux flèches, presque inoffensif contre les unités.",
    );

    val isMilitary get() = category != Category.VILLAGER
    val radius get() = if (category == Category.CAVALRY || category == Category.SIEGE) 0.32f else 0.22f
}

enum class Tech(
    val label: String,
    val icon: String,
    val cost: Cost,
    val time: Float,
    val minAge: Age,
    val researchedAt: BuildingType,
    val description: String,
) {
    WHEELBARROW("Brouette", "@wheelbarrow", Cost(food = 175, wood = 50), 40f, Age.FEUDAL, BuildingType.TOWN_CENTER, "Villageois +10% vitesse, +5 capacité"),
    DOUBLE_BIT_AXE("Hache à double tranchant", "@axe", Cost(food = 100, wood = 50), 25f, Age.FEUDAL, BuildingType.LUMBER_CAMP, "Bois +20%"),
    HORSE_COLLAR("Collier d'épaule", "🐂", Cost(food = 75, wood = 75), 20f, Age.FEUDAL, BuildingType.MILL, "Fermes +40% de nourriture"),
    GOLD_MINING("Extraction de l'or", "⛏️", Cost(food = 100, wood = 75), 30f, Age.FEUDAL, BuildingType.MINING_CAMP, "Or et pierre +15%"),
    FORGING("Forgeage", "🔨", Cost(food = 150), 30f, Age.FEUDAL, BuildingType.BLACKSMITH, "Infanterie et cavalerie +1 attaque"),
    SCALE_MAIL("Armure d'écailles", "🛡️", Cost(food = 100), 30f, Age.FEUDAL, BuildingType.BLACKSMITH, "Infanterie et cavalerie +1/+1 armure"),
    FLETCHING("Empennage", "🎯", Cost(food = 100, gold = 50), 30f, Age.FEUDAL, BuildingType.BLACKSMITH, "Archers, tours, forum +1 attaque et portée"),
    IRON_CASTING("Fonte du fer", "🔥", Cost(food = 220, gold = 120), 40f, Age.CASTLE, BuildingType.BLACKSMITH, "Infanterie et cavalerie +1 attaque"),
    MASONRY("Maçonnerie", "🏰", Cost(wood = 175, stone = 150), 40f, Age.CASTLE, BuildingType.TOWN_CENTER, "Bâtiments +20% PV"),
}

/** Gaia resource node kinds. */
enum class NodeKind(val label: String, val resource: ResourceType, val amount: Int) {
    TREE("Arbre", ResourceType.WOOD, 100),
    BERRIES("Baies", ResourceType.FOOD, 150),
    GOLD("Mine d'or", ResourceType.GOLD, 800),
    STONE("Carrière de pierre", ResourceType.STONE, 350),
}

enum class Terrain(val walkable: Boolean, val buildable: Boolean) {
    GRASS(true, true),
    DIRT(true, true),
    SAND(true, true),
    WATER(false, false),
}
