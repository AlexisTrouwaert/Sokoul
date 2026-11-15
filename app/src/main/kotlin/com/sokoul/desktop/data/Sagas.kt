package com.sokoul.desktop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Grande franchise décrite à la main (`curated_sagas.json`, le même fichier que la tablette) : plusieurs collections
 * TMDB réunies, films hors collection, ordre du récit et séries du même univers.
 */
data class CuratedSaga(
    val key: String, val name: String, val collectionIds: List<Int>, val extraMovieIds: List<Int>,
    val chronologicalOrderIds: List<Int>, val relatedSeriesTmdbIds: List<Int>,
    /** English is the base name; [nameFr] its translation. */
    val nameFr: String = name,
    /** Logo chosen by hand among TMDB's and fanart.tv's, since TMDB has none for collections. */
    val logo: String? = null,
    val collectionLogos: Map<Int, String> = emptyMap(),
)

object CuratedSagas {
    val all: List<CuratedSaga> by lazy {
        runCatching { CuratedSagas::class.java.getResourceAsStream("/curated_sagas.json")?.use { parse(it.readBytes().decodeToString()) } }.getOrNull().orEmpty()
    }

    fun parse(text: String): List<CuratedSaga> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        fun ids(o: JsonObject, k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
        return (root["sagas"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CuratedSaga(
                o["key"]?.jsonPrimitive?.content ?: return@mapNotNull null, o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                ids(o, "collectionIds"), ids(o, "extraMovieIds"), ids(o, "chronologicalOrderIds"), ids(o, "relatedSeriesTmdbIds"),
                o["nameFr"]?.jsonPrimitive?.content ?: o["name"]!!.jsonPrimitive.content,
                o["logo"]?.jsonPrimitive?.content,
                (o["collectionLogos"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.jsonPrimitive.content } }.toMap(),
            )
        }
    }

    /** Franchise qui réunit la collection TMDB [collectionId], s'il y en a une. */
    fun forCollection(collectionId: Int, sagas: List<CuratedSaga> = all) = sagas.firstOrNull { collectionId in it.collectionIds }
}

/** Film d'une saga, tel que l'affiche la page (frise, prochain film). */
data class SagaFilm(val id: Int, val title: String, val year: Int?, val poster: String?, val backdrop: String?, val rating: Double?)
