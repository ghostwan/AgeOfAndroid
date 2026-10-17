# Age of Android

Un jeu de stratégie en temps réel inspiré d'**Age of Empires II**, repensé pour le mobile (paysage, tactile).
100 % Kotlin + Jetpack Compose, aucun asset graphique : tout est dessiné de façon procédurale en vue isométrique.

| Menu | En jeu |
|---|---|
| ![menu](docs/menu.png) | ![jeu](docs/game.png) |

## Contenu

- **4 ressources** : nourriture (baies, fermes ressemées automatiquement), bois, or, pierre. Les villageois récoltent, portent 10 unités (15 avec la Brouette) et déposent au forum ou au camp le plus proche.
- **4 âges** : sombre → féodal → châteaux → impérial, avancés depuis le forum.
- **13 bâtiments** : forum, maison, moulin, camps de bûcherons/mineurs, ferme, caserne, camp de tir, écurie, forge, tour de guet, atelier de siège, merveille.
- **7 unités** avec un système de contres (armure de mêlée / perçante, bonus) : villageois, milicien, piquier (anti-cavalerie), archer, éclaireur, chevalier, bélier (anti-bâtiments).
- **9 technologies** (Brouette, Forgeage, Empennage, Armure d'écailles, Maçonnerie…).
- **4 civilisations** avec bonus : Francs, Bretons, Teutons, Japonais.
- **IA** (1 à 3 adversaires, 3 niveaux) : économie, maisons, camps près des ressources, passage des âges, vagues d'attaque, défense (y compris des villageois).
- Brouillard de guerre, mini-carte, file de production, point de ralliement, vitesse x1 à x3.
- **Sauvegarde** : automatique quand l'application passe en arrière-plan, à la pause et en quittant la partie, plus un bouton 💾 dans le menu pause. « Continuer » sur l'écran d'accueil reprend la partie (un seul emplacement ; une partie terminée est effacée).
- **Victoire** : détruire tous les ennemis, ou construire une Merveille et la garder 3 minutes.

## Contrôles tactiles

| Geste | Action |
|---|---|
| Toucher | Sélectionner une unité / un bâtiment |
| Appui long + glisser | Sélection de groupe (les militaires sont privilégiés) |
| Bouton ⬚ (barre du haut) | Mode groupe : glisser un doigt encadre directement (les cadres s'additionnent), toucher une unité l'ajoute ou la retire, toucher la destination donne l'ordre et quitte le mode |
| Double-tap sur une unité | Sélectionne toutes vos unités de ce type visibles à l'écran |
| Glisser / pincer | Déplacer la caméra / zoomer |
| Unités sélectionnées + toucher la carte | Ordre contextuel : déplacer, récolter, construire, attaquer (toucher un de vos bâtiments le sélectionne, sauf chantier, bâtiment endommagé ou ferme libre) |
| Villageois + toucher un bâtiment endommagé | Réparation : une réparation complète coûte la moitié du prix du bâtiment, payée au fur et à mesure |
| Bouton de construction | Affiche à quoi sert le bâtiment (unités, améliorations, temps, PV) ; s'il est verrouillé ou trop cher, une fiche indique ce qui manque |
| Appui long sur un bouton (bâtiment, unité, amélioration, âge) | Fiche détaillée : rôle, forces et faiblesses, stats, ce que ça débloque. Un bouton verrouillé ou trop cher ouvre aussi la fiche en le touchant |
| Bâtiment sélectionné + toucher la carte | Point de ralliement |
| 💤👷 / ⚔️ / 🏛️ | Villageois inactif suivant / toute l'armée / forum |
| ▼ / ▲ | Replier / déplier la barre du bas (mini-carte et actions) |

## Multijoueur (Wi-Fi local)

Menu › **👥 Multijoueur**, sur des téléphones connectés au même réseau Wi-Fi :

1. Un joueur choisit son nom et sa civilisation puis **Créer une partie**. Il règle le mode, la taille de la carte, ajoute des IA si besoin, puis **Lancer la partie**.
2. Les autres rejoignent au choix :
   - en **scannant le QR code** affiché dans le salon de l'hôte, avec l'appareil photo du téléphone (le lien `ageofandroid://join?…` ouvre directement le jeu) ou avec Multijoueur › **📷 Scanner** ;
   - en choisissant la partie dans **Rejoindre une partie** (découverte automatique sur le Wi-Fi) ;
   - en saisissant l'adresse IP affichée chez l'hôte.

Modes (jusqu'à 4 places, joueurs et IA) :

- **Coopération** : tous les joueurs dans la même équipe (vision partagée, bases voisines) contre une ou plusieurs IA.
- **Chacun pour soi** : 1 contre 1, 1 contre 1 contre 1… avec des IA en plus si on veut.

Règles : pas de pause ni d'accélération, pas de sauvegarde ni de révélation de la carte. Si un invité quitte la partie, une IA prend sa place. Si l'hôte quitte ou met l'application en arrière-plan, la partie s'arrête (ou se fige) pour tout le monde.

Fonctionnement : l'hôte fait tourner la seule vraie simulation. Les invités lui envoient leurs ordres (`Command`) et reçoivent 10 fois par seconde un instantané compressé de la partie (`Snapshot`), entre lesquels le mouvement des unités est interpolé. Le transport (`net/Link`) est indépendant du réseau : TCP en local aujourd'hui, un relais Internet (WebSocket) pourra le remplacer sans toucher au reste.

## Installer sur son téléphone

1. Ouvrez la page [Releases](https://github.com/ghostwan/AgeOfAndroid/releases) depuis le téléphone et téléchargez `AgeOfAndroid-vX.Y.Z.apk`.
2. Ouvrez le fichier : Android demande d'autoriser l'installation depuis le navigateur ou le gestionnaire de fichiers (*Installer des applis inconnues*). Acceptez.
3. Ou depuis un ordinateur : `adb install AgeOfAndroid-vX.Y.Z.apk`.

Toutes les builds (CI et locales) sont signées avec la même clé, `app/debug.keystore`, versionnée dans le dépôt : une nouvelle version s'installe par-dessus la précédente et garde la sauvegarde. Cette clé est publique, elle ne convient pas pour une publication sur le Play Store. Les versions antérieures à 0.3.2 utilisaient une clé aléatoire : il faut les désinstaller une dernière fois.

## Publier une release

Le workflow `.github/workflows/release.yml` (tag `v*`, push sur `master`/`claude/**`, ou lancement manuel) lance les tests, construit l'APK release et publie la release `v<versionName>` avec l'APK attaché. Incrémentez `versionName` dans `app/build.gradle.kts` pour une nouvelle release.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Développement

```bash
./gradlew :app:testDebugUnitTest   # moteur (JVM) + captures d'écran Robolectric dans app/build/screenshots
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # APK publié (non debuggable, plus fluide) : app/build/outputs/apk/release/app-release.apk
```

Architecture :

- `game/` : moteur pur Kotlin, testable sur la JVM. `World` (simulation, ordres, combat, brouillard), `GameMap` (grille + A*), `MapGenerator`, `AiPlayer`, `Types` (données d'équilibrage), `Command` (ordres des joueurs), `Snapshot` (synchronisation multijoueur).
- `net/` : multijoueur. `Link`/`TcpLink` (transport), `Protocol` (messages, salon), `Sessions` (`HostSession`, `GuestSession`), `LanDiscovery` (recherche des parties en mDNS), `JoinLink` (lien des QR codes).
- `ui/` : Compose. `GameController` (caméra, sélection, entrées), `GameRenderer` (rendu isométrique sur Canvas : terrain et brouillard en textures pivotées à 45°), `Hud`, `GameScreen` (boucle `withFrameNanos` + gestes), `App` (menu).
