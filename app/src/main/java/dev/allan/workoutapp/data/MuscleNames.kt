package dev.allan.workoutapp.data

/**
 * Localized display names for wger muscles, keyed by wger's English name.
 * wger ships latin + English only; pt-BR and German are maintained here.
 * Unknown muscles fall back to the English name from the snapshot.
 */
object MuscleNames {

    private data class Names(val pt: String, val de: String)

    private val map = mapOf(
        "Biceps" to Names("Bíceps", "Bizeps"),
        "Shoulders" to Names("Ombros", "Schultern"),
        "Serratus anterior" to Names("Serrátil anterior", "Sägemuskel"),
        "Chest" to Names("Peitoral", "Brust"),
        "Triceps" to Names("Tríceps", "Trizeps"),
        "Abs" to Names("Abdômen", "Bauchmuskeln"),
        "Calves" to Names("Panturrilhas", "Waden"),
        "Glutes" to Names("Glúteos", "Gesäßmuskeln"),
        "Trapezius" to Names("Trapézio", "Trapezmuskel"),
        "Quads" to Names("Quadríceps", "Oberschenkel vorne"),
        "Hamstrings" to Names("Posteriores de coxa", "Beinbeuger"),
        "Lats" to Names("Dorsais", "Latissimus"),
        "Brachialis" to Names("Braquial", "Armbeuger"),
        "Obliques" to Names("Oblíquos", "Schräge Bauchmuskeln"),
        "Soleus" to Names("Sóleo", "Schollenmuskel"),
    )

    fun display(nameEn: String, lang: String): String = when (lang) {
        "pt" -> map[nameEn]?.pt ?: nameEn
        "de" -> map[nameEn]?.de ?: nameEn
        else -> nameEn
    }
}
