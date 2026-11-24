# Premier démarrage Sokoul — plan (tablette d'abord)

Statut : brief à valider, aucun code écrit. Fiche produit : `PRODUCT.md`.
Cible n° 1 : Galaxy Tab S7 FE (1600×2560, tactile, portrait + paysage). Ensuite : Google TV (télécommande), puis PC.

## 1. Le problème
Aujourd'hui, après l'animation, `SetupScreen` affiche **un seul formulaire** :
- serveur, identifiant et mot de passe IPTV ;
- 13 clés API (TMDB, OMDb, Watchmode…) ;
- l'URL du backend anime.

Il est pensé télécommande, s'affiche à l'échelle 1920 px sur la tablette, et parle le jargon des développeurs. Pour un **public large** (réponse du 20/11), c'est un mur : personne ne sait ce qu'est « Movie of the Night » ni pourquoi coller une clé.

**Objectif** : de l'animation à un catalogue rempli et jouable en **moins de 2 minutes**, sans jamais taper une clé longue, et en sachant toujours ce que chaque source apporte.

**Le moment « aha »** : l'accueil de Sokoul se remplit d'affiches et de chaînes **pendant** que la synchronisation tourne, juste après le récapitulatif.

## 2. Parcours

```
Animation Sokoul
   └─▶ Bienvenue ──▶ « Comment voulez-vous configurer ? »
                          ├─ Avec mon téléphone (QR)          ← recommandé
                          ├─ Avec mon compte Sokoul (code + QR)
                          ├─ Importer depuis Sokoul PC (détecté sur le réseau)
                          └─ Ici, sur la tablette
                                   └─▶ Sources ─▶ Profils ─▶ Préférences ─▶ Prêt ─▶ Accueil qui se remplit
```

Toutes les voies mènent au même écran **Sources**. Il est rempli au fur et à mesure, quelle que soit l'origine de chaque information : téléphone, compte, PC ou saisie directe.

### Écran 1 — Bienvenue (à la sortie de l'animation)
- Le logo de l'animation **glisse et rétrécit** jusqu'à sa place en haut : pas de coupure entre l'intro et la page.
- Une phrase de valeur, une seule, par exemple : « Toutes vos chaînes, films et séries au même endroit ».
- Langue de l'interface pré-sélectionnée d'après le système (FR/EN/DE existent), modifiable d'un toucher.
- Bouton principal **Commencer**. Lien secondaire **J'ai déjà un compte Sokoul**, qui mène directement à la voie compte.
- Temps annoncé honnêtement : « Environ 2 minutes ».

### Écran 2 — Comment voulez-vous configurer ?
Quatre grandes cartes, chacune avec un pictogramme, un titre et une ligne d'explication.

1. **Avec mon téléphone** — *Recommandé*
   - La tablette affiche un **QR code**. Le téléphone ouvre une page de configuration servie **par la tablette elle-même** sur le Wi-Fi local : pas d'internet, pas de compte.
   - Chaque information saisie sur le téléphone **apparaît en direct sur la tablette**, avec une coche animée une fois validée.
2. **Avec mon compte Sokoul**
   - Code à 5 caractères + QR, via le flux `/api/tv/link` qui existe déjà sur le serveur.
   - Récupère les profils, la progression et, si l'utilisateur l'a choisi, la configuration enregistrée dans le compte.
3. **Importer depuis Sokoul PC**
   - N'apparaît **que si** un PC Sokoul est détecté sur le réseau.
   - Le PC affiche un code de confirmation, puis tout est transféré : IPTV, clés, serveur Sokoul.
4. **Ici, sur la tablette** : la saisie classique, mais guidée.

En bas : **Plus tard** (on entre dans l'appli avec ce qui existe déjà).

### Écran 3 — Sources (le cœur)
Des cartes empilées, chacune **facultative**, avec un état clair : *Non configuré · En vérification… · Prêt ✓ · Problème*.

| Carte | Ce qu'on voit | Comment on la remplit | Ce qu'on affiche une fois prête |
|---|---|---|---|
| **Chaînes TV** (IPTV) | « Vos chaînes en direct et votre vidéothèque IPTV » | Onglets **Xtream** / **Lien M3U** / **Depuis mon téléphone** | « 1 240 chaînes · 8 300 films · actif jusqu'au 12/03 · 2 écrans » (infos du compte Xtream) |
| **Real-Debrid** | « Des films et séries en haute qualité » | **Code appareil** : la tablette affiche un code + QR vers `real-debrid.com/device`, l'utilisateur valide, c'est fini. Plus aucune clé à coller. | Nom du compte, jours premium restants |
| **Serveur Sokoul** | « Plus de sources web et d'anime » | **Détecté automatiquement** sur le réseau local, ou adresse manuelle | « Connecté au PC de la maison » |
| **Sous-titres** | « Sous-titres en français et en anglais » | OpenSubtitles / Subdl : clé intégrée par défaut, la sienne en option | « Activés » |
| **Avancé** (replié) | « Pour les experts : vos propres clés » | Les autres clés regroupées **par bénéfice** (fiches enrichies, recommandations…), jamais par nom de fournisseur seul | Masque `•••• 1234` |

Règles :
- Chaque carte s'ouvre en **feuille** (panneau glissant depuis le bas en portrait, depuis la droite en paysage). Fermer la feuille garde ce qui a été tapé.
- **Validation en direct** avec `CredentialValidator` (existant) dès que la saisie est complète, sans bouton « Vérifier ».
- **Erreurs en langage clair** : « Identifiant ou mot de passe refusé par votre fournisseur », « Serveur injoignable : vérifiez l'adresse », jamais un code HTTP.
- Bouton **Continuer** toujours actif, même avec zéro source : l'appli fonctionne alors en mode découverte (catalogue TMDB et bandes-annonces).

### Écran 4 — Profils
- Qui regarde ? Un ou plusieurs profils, avatars, **profil Enfants**.
- Avec un compte Sokoul, les profils du compte sont **déjà là**.

### Écran 5 — Préférences rapides (3 choix max)
- Langue audio préférée : **VF / VOSTFR / Original**.
- Sous-titres : auto / toujours / jamais.
- Lecture automatique des bandes-annonces : oui / non.

### Écran 6 — C'est prêt
- Récapitulatif d'une ligne par source, avec sa coche.
- **Entrer dans Sokoul** : l'accueil s'ouvre et se remplit en direct pendant la synchronisation. Une barre discrète « Chargement de vos chaînes… 640/1 240 » se termine toute seule.

## 3. Navigation et mise en page (tablette)
- **Paysage** : colonne gauche étroite = étapes (1 Bienvenue · 2 Sources · 3 Profils · 4 Prêt), avec l'étape courante en surbrillance ; contenu à droite ; feuilles depuis la droite.
- **Portrait** : indicateur d'étapes en haut (points + titre), une seule colonne, **barre d'action collée au-dessus du clavier** (insets IME), feuilles depuis le bas.
- **Retour système** : revient toujours d'un cran et ne perd jamais une saisie.
- **Reprise** : si l'appli est quittée en cours de route, on revient à la même étape (état persisté).
- **Cibles tactiles** ≥ 48 dp. Taille de police système respectée. Clavier matériel : Tab, Entrée et Échap fonctionnent.
- **Langage visuel** : celui d'Apple TV, qui est engageant (cf. PRODUCT.md), avec les panneaux verre de `ui/Panels.kt` et les jetons de `theme/Theme.kt`, un seul bord de texte, et l'état actif = remplissage + léger soulèvement. Sur tablette, l'appui joue le rôle du focus.
- **Mouvement** : transitions en « axe partagé » entre étapes, coches qui se dessinent, et le logo de l'intro qui devient l'en-tête. Si l'utilisateur coupe les animations dans Android, on passe en fondu simple.

## 4. Ce que j'ajoute sans que tu l'aies demandé
1. **Configuration par QR depuis le téléphone**, sans internet ni compte (serveur local éphémère sur la tablette).
2. **Real-Debrid par code appareil** au lieu de la clé API.
3. **Détection automatique** du serveur Sokoul et de Sokoul PC sur le réseau.
4. **Infos d'abonnement IPTV** visibles : expiration, nombre d'écrans, nombre de chaînes.
5. **Mode découverte** sans aucune source, pour ne jamais bloquer l'entrée dans l'appli.
6. **Reprise** là où on s'était arrêté, et **« Plus tard »** partout.
7. **Aide contextuelle** : un « ? » sur chaque carte ouvre une explication de 3 lignes (« C'est quoi l'IPTV ? »).
8. **Confidentialité affichée** : « Vos identifiants restent sur cet appareil » (ou dans votre compte, si vous le choisissez).
9. **Réutilisation** : les mêmes cartes deviennent la page **Réglages → Sources**. Un seul composant à maintenir.

## 5. Architecture technique
| Brique | Où | Remarque |
|---|---|---|
| Nouveau `FormFactor.Tablet` (tactile, ≥ 600 dp, non-TV) | `ui/FormFactor.kt`, `tv/MainActivity.kt` | Aujourd'hui la tablette = `Tv`. Les autres écrans gardent le rendu `Tv` jusqu'à leur adaptation : on audite les `Form.current == Tv` avant de basculer. |
| Machine à états du parcours (`OnboardingFlow`) | `pc/startup/` | Étapes, reprise persistée (`pc_onboarding_step`), compatible avec `StartupRoute`. |
| Cartes de source + feuilles | `pc/startup/onboarding/` | Réutilisent `PanelCard`, `PanelOption`, `glassSurface`, `panelEnter`. |
| Validation / enregistrement | `SetupController` (existant) | À découper par carte (IPTV, debrid, clés), en gardant ses garanties : rien n'est enregistré avant validation, saisies masquées. |
| Real-Debrid code appareil | `core/.../debrid/RealDebridApi.kt` | `/oauth/v2/device/code` → `/device/credentials` → `/token`, rafraîchissement du jeton. |
| Infos d'abonnement Xtream | `XtreamClient.account()` (existant) | Exposer expiration, nombre de connexions max et nombre de chaînes. |
| Serveur local d'appairage (QR téléphone) | nouveau, côté Android | Écoute sur le Wi-Fi local, jeton à usage unique dans l'URL, expire en 10 min, page HTML embarquée, s'arrête à la fin du parcours. |
| QR code | dépendance `zxing:core` (ou encodeur léger) | Rendu Compose. |
| Découverte réseau | NSD Android (`_sokoul._tcp`) | Le PC et le serveur Sokoul s'annoncent. |
| Compte Sokoul | client de `/api/tv/link` (`/start`, `/poll`) + `/api/sync` | JWT stocké chiffré ; dépend de la décision n° 1. |

## 6. Découpage
- **P1 — Tablette, saisie guidée** (ce sprint) :
  - `FormFactor.Tablet` ;
  - Bienvenue, Sources (IPTV Xtream/M3U, Real-Debrid par code, Sous-titres, Avancé), Profils, Préférences, Prêt ;
  - accueil qui se remplit.
- **P2 — Configuration depuis le téléphone (QR)** : serveur local et page mobile.
- **P3 — Compte Sokoul** : liaison, profils et synchro (selon les décisions ci-dessous).
- **P4 — Import Sokoul PC + version télécommande** pour Google TV, et reprise dans Réglages.

## 7. Vérification
- Tests unitaires de la machine à états et de chaque carte (validation, erreurs, reprise).
- `pm clear com.sokoul.tv` pour rejouer le premier démarrage. Captures sur la tablette en **portrait et paysage**, avec **clavier ouvert**, à **taille de police 1,3**.
- Comparaison avec les règles Apple TV de `PRODUCT.md`, avant de dire « fini ».
- Chronométrage du parcours complet : objectif < 2 min avec IPTV + Real-Debrid.

## 8. Décisions (20/11/2026)
1. **Site de validation du code compte** : reporté à la P3.
2. **Configuration dans le compte** : **oui, au choix**. Une case « Sauvegarder dans mon compte », décochée par défaut ; les données sont chiffrées.
3. **Autres hébergeurs** (AllDebrid, Premiumize, TorBox…) : encore ouvert.

## 9. Ce que contient la P1 (livrée sur `feat/onboarding-tablet`)
- `Form.tablet` (tactile, ≥ 600 dp, non-TV) : seul le premier démarrage change de rendu sur tablette ; le reste de l'appli garde le rendu `Tv`.
- Parcours Bienvenue → Sources → Visionnage → Prêt, étape persistée (`pc_onboarding_step`).
- Cartes : Chaînes TV (identifiants **ou** lien M3U, avec infos d'abonnement), Real-Debrid (**code appareil + QR**, ou jeton privé), Sous-titres, Serveur Sokoul (adresse du PC), Clés expertes regroupées par bénéfice.
- Jeton Real-Debrid renouvelé automatiquement au démarrage (`RealDebridAuth`).
- Le choix de la méthode (téléphone / compte / PC) arrive avec la P2 : tant qu'une seule méthode existe, l'écran de choix ne s'affiche pas.
