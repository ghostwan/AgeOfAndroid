package com.ghostwan.ageofandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostwan.ageofandroid.BuildConfig

/** Version shown to the player, e.g. "v0.4.3". */
val APP_VERSION = "v" + BuildConfig.VERSION_NAME

class Release(val version: String, val date: String, val changes: List<String>)

/** Newest first. Add an entry with every version bump. */
val CHANGELOG = listOf(
    Release(
        "0.5.1", "18/10/2026",
        listOf(
            "Port ⚓ : se construit dans l'eau contre la rive, dès l'Âge sombre",
            "Bateaux de pêche ⛵ : pêchent même loin de la rive, plus vite qu'un villageois, et déchargent 15 de nourriture au port",
            "Les navires ne naviguent que sur l'eau ; seuls les archers et les tours peuvent les atteindre",
        ),
    ),
    Release(
        "0.5.0", "18/10/2026",
        listOf(
            "Nouveaux types de carte : Hautes terres (montagnes infranchissables et cols), Canyons (ravins à traverser par quelques passages) et Grands lacs",
            "Pêche : les villageois pêchent à la main les poissons proches de la rive",
            "Le type de carte se choisit dans le menu et dans le salon multijoueur",
        ),
    ),
    Release(
        "0.4.3", "18/10/2026",
        listOf(
            "Numéro de version et liste des nouveautés sur l'écran d'accueil",
        ),
    ),
    Release(
        "0.4.2", "17/10/2026",
        listOf(
            "Réparation : sélectionnez des villageois puis touchez un bâtiment endommagé (coûte la moitié de son prix pour une réparation complète)",
        ),
    ),
    Release("0.4.1", "17/10/2026", listOf("Rejoindre une partie multijoueur en scannant le QR code de l'hôte")),
    Release(
        "0.4.0", "16/10/2026",
        listOf(
            "Multijoueur en réseau local : coopération contre l'IA ou chacun pour soi, jusqu'à 4 joueurs",
            "Les parties sont trouvées automatiquement sur le Wi-Fi, ou en tapant l'adresse IP de l'hôte",
            "Une IA reprend la civilisation d'un joueur qui quitte la partie",
        ),
    ),
    Release(
        "0.3.8", "16/10/2026",
        listOf(
            "Fini les ralentissements dans les grandes batailles de fin de partie",
            "Les APK publiés sont des versions optimisées (release)",
        ),
    ),
    Release("0.3.7", "16/10/2026", listOf("La barre du bas se déplie quand on sélectionne une unité ou un bâtiment")),
    Release("0.3.6", "16/10/2026", listOf("Sélection de groupe : bouton ⬚ puis glisser, ou double tap sur une unité")),
    Release("0.3.5", "16/10/2026", listOf("Fiches détaillées des unités, technologies et âges (appui long)")),
    Release("0.3.4", "16/10/2026", listOf("Chaque bâtiment explique à quoi il sert avant d'être construit")),
    Release(
        "0.3.3", "16/10/2026",
        listOf(
            "Les mises à jour s'installent par-dessus la version précédente",
            "Boutons du menu principal plus coupés sur les petits écrans",
        ),
    ),
    Release("0.3.1", "16/10/2026", listOf("Panneau de sélection plus lisible")),
    Release("0.3.0", "15/10/2026", listOf("Sauvegarde automatique, sauvegarde depuis la pause et bouton « Continuer »")),
    Release("0.2.0", "15/10/2026", listOf("Barre du bas repliable, minuteurs de production, panneau de sélection lisible")),
    Release("0.1.0", "15/10/2026", listOf("Première version : 4 civilisations, 4 âges, IA, brouillard de guerre, Merveille")),
)

@Composable
fun ChangelogList() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CHANGELOG.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "v${r.version}" + (if (r.version == BuildConfig.VERSION_NAME) " (installée)" else "") + "  •  ${r.date}",
                    color = BorderGold, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
                r.changes.forEach { Text("• $it", fontSize = 13.sp) }
            }
        }
    }
}
