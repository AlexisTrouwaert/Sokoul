package com.sokoul.pc.data

/** Where a saga is shelved on the collections page. Declaration order is the page's order. */
enum class SagaCategory(val title: L) {
    Superheroes(L("Superheroes", "Super-héros")),
    Action(L("Action & Adventure", "Action et aventure")),
    SciFi(L("Science Fiction", "Science-fiction")),
    Fantasy(L("Fantasy", "Fantastique")),
    Animation(L("Animation", "Animation")),
    Family(L("Family", "En famille")),
    Horror(L("Horror", "Horreur")),
    Crime(L("Crime & Thriller", "Policier et thriller")),
    Comedy(L("Comedy", "Comédie")),
    Drama(L("Drama & Romance", "Drame et romance")),
    History(L("War, History & Western", "Guerre, histoire et western")),
    ;

    companion object {
        fun named(name: String?): SagaCategory = entries.firstOrNull { it.name == name } ?: Action
    }
}

/** Sorts a saga by what most of its films are. Pure and tested. */
object SagaCategories {
    private const val ANIMATION = 16
    private const val HORROR = 27
    private const val SCIFI = 878
    private const val FANTASY = 14
    private const val FAMILY = 10751
    private const val COMEDY = 35
    private const val ACTION = 28
    private const val ADVENTURE = 12
    private const val CRIME = 80
    private const val THRILLER = 53
    private const val DRAMA = 18
    private const val ROMANCE = 10749
    private const val WAR = 10752
    private const val HISTORY = 36
    private const val WESTERN = 37
    private const val MAJORITY = 0.5
    private const val SUPERHERO_FILMS = 2

    /** [films] holds each film's TMDB genres; [keywords] those of its best known films. The most telling genre wins. */
    fun of(films: List<Collection<Int>>, keywords: Collection<String> = emptyList()): SagaCategory {
        // One film mentioning it is not enough: Men in Black has a single such keyword and is no superhero saga.
        if (keywords.count { it.equals("superhero", ignoreCase = true) } >= SUPERHERO_FILMS) return SagaCategory.Superheroes
        fun most(vararg ids: Int): Boolean =
            films.isNotEmpty() && films.count { f -> ids.any { it in f } }.toDouble() / films.size >= MAJORITY
        return when {
            most(ANIMATION) -> SagaCategory.Animation
            most(HORROR) -> SagaCategory.Horror
            most(SCIFI) -> SagaCategory.SciFi
            most(FANTASY) -> SagaCategory.Fantasy
            most(FAMILY) -> SagaCategory.Family
            most(COMEDY) && !most(ACTION) -> SagaCategory.Comedy
            most(WAR, HISTORY, WESTERN) -> SagaCategory.History
            most(ACTION, ADVENTURE) -> SagaCategory.Action
            most(CRIME, THRILLER) -> SagaCategory.Crime
            most(DRAMA, ROMANCE) -> SagaCategory.Drama
            most(COMEDY) -> SagaCategory.Comedy
            else -> SagaCategory.Action
        }
    }
}
