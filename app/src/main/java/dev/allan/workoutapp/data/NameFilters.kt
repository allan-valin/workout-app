package dev.allan.workoutapp.data

/**
 * Structured name filters (Allan): exercise names describe equipment, body position and
 * movement pattern — so three dropdowns refine a search by token-matching the LOCALIZED
 * hit name. Canonical keys are English; each key carries en/pt/de name variants so the
 * filters work on wger's translated names and on free-exercise-db's English ones.
 * Token tables are heuristics — a miss just means the exercise stays visible under "All".
 */
object NameFilters {

    val EQUIPMENT: Map<String, List<String>> = linkedMapOf(
        "barbell" to listOf("barbell", "barra", "langhantel"),
        "dumbbell" to listOf("dumbbell", "halter", "halteres", "kurzhantel"),
        "cable" to listOf("cable", "polia", "cabo", "kabel", "crossover", "cross-over"),
        "machine" to listOf("machine", "máquina", "maquina", "maschine", "smith", "hammer", "hack"),
        "kettlebell" to listOf("kettlebell"),
        "band" to listOf("band", "elástico", "elastico", "faixa", "suspens", "trx"),
        "bodyweight" to listOf(
            "bodyweight", "body weight", "peso corporal", "eigengewicht",
            "push-up", "pushup", "flexão de braço", "pull-up", "pullup", "chin-up",
            "barra fixa", "dip", "mergulho", "liegestütz", "klimmzug",
        ),
    )

    val POSITION: Map<String, List<String>> = linkedMapOf(
        "standing" to listOf("standing", "em pé", "de pé", "stehend"),
        "seated" to listOf("seated", "sitting", "sentado", "sitzend"),
        "lying" to listOf("lying", "deitado", "liegend", "supine", "prone"),
        "incline" to listOf("incline", "inclinado", "inclinada", "schräg"),
        "decline" to listOf("decline", "declinado", "declinada", "negativ"),
        "bent-over" to listOf("bent-over", "bent over", "curvado", "curvada", "vorgebeugt"),
        "kneeling" to listOf("kneeling", "ajoelhado", "kniend"),
    )

    val PATTERN: Map<String, List<String>> = linkedMapOf(
        "press" to listOf("press", "supino", "desenvolvimento", "drücken", "drucken"),
        "curl" to listOf("curl", "rosca"),
        "row" to listOf("row", "remada", "rudern"),
        "raise" to listOf("raise", "lateral", "elevação", "elevacao", "heben"),
        "extension" to listOf("extension", "extensão", "extensao", "testa", "strecken"),
        "fly" to listOf("fly", "flye", "crucifixo", "voador", "fliegende", "peck deck", "pec deck"),
        "squat" to listOf("squat", "agachamento", "kniebeuge"),
        "lunge" to listOf("lunge", "afundo", "avanço", "avanco", "passada", "ausfallschritt"),
        "deadlift" to listOf("deadlift", "terra", "stiff", "kreuzheben"),
        "pulldown" to listOf("pulldown", "pull-down", "puxada", "pulley", "latzug"),
        "pull-up" to listOf("pull-up", "pullup", "chin-up", "barra fixa", "klimmzug"),
        "crunch" to listOf("crunch", "abdominal", "sit-up", "situp"),
        "plank" to listOf("plank", "prancha", "planke"),
        "twist" to listOf("twist", "rotação", "rotacao", "rotation", "oblíquo", "obliquo"),
    )

    /** Localized dropdown label for a canonical key (gym vocabulary, kept short). */
    fun label(key: String, lang: String): String {
        val pt = mapOf(
            "barbell" to "Barra", "dumbbell" to "Halteres", "cable" to "Polia",
            "machine" to "Máquina", "kettlebell" to "Kettlebell", "band" to "Elástico/TRX",
            "bodyweight" to "Peso corporal",
            "standing" to "Em pé", "seated" to "Sentado", "lying" to "Deitado",
            "incline" to "Inclinado", "decline" to "Declinado", "bent-over" to "Curvado",
            "kneeling" to "Ajoelhado",
            "press" to "Pressão/Supino", "curl" to "Rosca", "row" to "Remada",
            "raise" to "Elevação", "extension" to "Extensão", "fly" to "Crucifixo/Voador",
            "squat" to "Agachamento", "lunge" to "Afundo", "deadlift" to "Terra",
            "pulldown" to "Puxada", "pull-up" to "Barra fixa", "crunch" to "Abdominal",
            "plank" to "Prancha", "twist" to "Rotação",
        )
        val de = mapOf(
            "barbell" to "Langhantel", "dumbbell" to "Kurzhantel", "cable" to "Kabel",
            "machine" to "Maschine", "kettlebell" to "Kettlebell", "band" to "Band/TRX",
            "bodyweight" to "Eigengewicht",
            "standing" to "Stehend", "seated" to "Sitzend", "lying" to "Liegend",
            "incline" to "Schräg aufwärts", "decline" to "Schräg abwärts",
            "bent-over" to "Vorgebeugt", "kneeling" to "Kniend",
            "press" to "Drücken", "curl" to "Curl", "row" to "Rudern",
            "raise" to "Heben", "extension" to "Strecken", "fly" to "Fliegende",
            "squat" to "Kniebeuge", "lunge" to "Ausfallschritt", "deadlift" to "Kreuzheben",
            "pulldown" to "Latzug", "pull-up" to "Klimmzug", "crunch" to "Crunch",
            "plank" to "Planke", "twist" to "Rotation",
        )
        return when (lang) {
            "pt" -> pt[key]
            "de" -> de[key]
            else -> null
        } ?: key.replaceFirstChar { it.uppercase() }
    }

    fun matches(name: String, key: String?, table: Map<String, List<String>>): Boolean {
        if (key == null) return true
        val n = name.lowercase()
        return table[key].orEmpty().any { n.contains(it) }
    }
}
