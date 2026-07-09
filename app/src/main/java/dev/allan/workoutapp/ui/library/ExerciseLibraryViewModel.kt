package dev.allan.workoutapp.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.ExerciseHit
import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.db.Muscle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which language pool the search runs against. */
enum class SearchLang { APP, EN, PT, DE, ALL }

data class LibraryUiState(
    val query: String = "",
    val searched: Boolean = false,
    val searching: Boolean = false,
    val results: List<ExerciseHit> = emptyList(),
    val selectedMuscleId: Int? = null,
    val searchLang: SearchLang = SearchLang.APP,
    val showAltNames: Boolean = false,
    /** When on, exercises hitting an injured muscle (primary or secondary) are hidden. */
    val excludeInjured: Boolean = true,
    /** exerciseId -> "alt names" preview line, filled when showAltNames is on. */
    val altNames: Map<String, String> = emptyMap(),
)

data class ExerciseDetail(
    val hit: ExerciseHit,
    val translations: List<ExerciseTranslation>,
    /** User-owned YouTube/video link (exercise_link table); edited here in the library. */
    val videoUrl: String? = null,
    /** Persistent per-exercise note. */
    val note: String = "",
)

class ExerciseLibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    private val _detail = MutableStateFlow<ExerciseDetail?>(null)
    val detail: StateFlow<ExerciseDetail?> = _detail

    val muscles: StateFlow<List<Muscle>> = db.exerciseDao().muscles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val injuredMuscles: StateFlow<Set<Int>> =
        dev.allan.workoutapp.data.Settings.injuredMuscles(app)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Starred exercise ids — drive the star icon and sort favorites to the top. */
    val favoriteIds: StateFlow<Set<String>> = db.exerciseDao().favoriteIdsFlow()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleFavorite(exerciseId: String) {
        viewModelScope.launch {
            if (exerciseId in favoriteIds.value) db.exerciseDao().removeFavorite(exerciseId)
            else db.exerciseDao().addFavorite(
                dev.allan.workoutapp.data.db.ExerciseFavorite(exerciseId)
            )
        }
    }

    /** Existing custom exercises — browsed, selected, and deleted from the customs sheet. */
    val customs: StateFlow<List<ExerciseHit>> = db.exerciseDao().customHits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _customsMessage = MutableStateFlow<String?>(null)
    val customsMessage: StateFlow<String?> = _customsMessage
    fun clearCustomsMessage() { _customsMessage.value = null }

    /** Delete a custom exercise; refuses (with a message) while workouts still use it. */
    fun deleteCustom(hit: ExerciseHit, inUseMessage: (Int) -> String) {
        viewModelScope.launch {
            val uses = db.exerciseDao().exerciseUsageCount(hit.id)
            if (uses > 0) {
                _customsMessage.value = inUseMessage(uses)
            } else {
                db.exerciseDao().deleteVideoLink(hit.id)
                db.exerciseDao().deleteTranslationsFor(hit.id)
                db.exerciseDao().deleteCustomExercise(hit.id)
            }
        }
    }

    fun setExcludeInjured(exclude: Boolean) {
        _state.value = _state.value.copy(excludeInjured = exclude)
        if (_state.value.searched) search(appLang())
    }

    fun setQuery(q: String) = _state.value.let { _state.value = it.copy(query = q) }

    fun setMuscle(id: Int?) {
        _state.value = _state.value.copy(selectedMuscleId = id)
        if (_state.value.searched) search(appLang())
    }

    fun setSearchLang(lang: SearchLang) {
        _state.value = _state.value.copy(searchLang = lang)
        if (_state.value.searched) search(appLang())
    }

    fun setShowAltNames(show: Boolean) {
        _state.value = _state.value.copy(showAltNames = show)
        if (_state.value.searched) search(appLang())
    }

    private fun appLang(): String {
        val tag = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            .toLanguageTags().ifEmpty { java.util.Locale.getDefault().toLanguageTag() }
        return when {
            tag.startsWith("pt") -> "pt"
            tag.startsWith("de") -> "de"
            else -> "en"
        }
    }

    /** Explicit search trigger — nothing runs while typing. */
    fun search(appLangOverride: String? = null) {
        val s = _state.value
        val lang = when (s.searchLang) {
            SearchLang.APP -> appLangOverride ?: appLang()
            SearchLang.EN -> "en"
            SearchLang.PT -> "pt"
            SearchLang.DE -> "de"
            SearchLang.ALL -> null
        }
        val muscleCsv = s.selectedMuscleId?.let { "%,$it,%" }
        _state.value = s.copy(searching = true)
        viewModelScope.launch {
            var hits = db.exerciseDao().search(s.query.trim(), lang, muscleCsv)
            if (s.excludeInjured) {
                val injured =
                    dev.allan.workoutapp.data.Settings.injuredMuscles(getApplication()).first()
                if (injured.isNotEmpty()) {
                    hits = hits.filter { hit ->
                        hit.primaryMuscles.none(injured::contains) &&
                            hit.secondaryMuscles.none(injured::contains)
                    }
                }
            }
            // Favorites float to the top (stable — name order kept within each group).
            val favs = db.exerciseDao().favoriteIds().toSet()
            val sorted = hits.sortedByDescending { it.id in favs }
            val alt = if (_state.value.showAltNames) buildAltNames(sorted) else emptyMap()
            _state.value = _state.value.copy(
                searching = false, searched = true, results = sorted, altNames = alt,
            )
        }
    }

    private suspend fun buildAltNames(hits: List<ExerciseHit>): Map<String, String> =
        hits.take(100).associate { hit ->
            val all = db.exerciseDao().translations(hit.id)
            val parts = all.flatMap { tr ->
                (listOf(tr.name) + tr.aliases).map { n -> if (n == hit.name) null else "$n (${tr.lang})" }
            }.filterNotNull().distinct()
            hit.id to parts.joinToString(" · ")
        }

    fun openDetail(hit: ExerciseHit) {
        viewModelScope.launch {
            _detail.value = ExerciseDetail(
                hit,
                db.exerciseDao().translations(hit.id),
                db.exerciseDao().videoLink(hit.id),
                db.sessionDao().noteText(hit.id) ?: "",
            )
        }
    }

    fun saveNote(exerciseId: String, text: String) {
        _detail.value = _detail.value?.let {
            if (it.hit.id == exerciseId) it.copy(note = text) else it
        }
        viewModelScope.launch { dev.allan.workoutapp.data.PlanRepo.saveExerciseNote(db, exerciseId, text) }
    }

    fun closeDetail() {
        _detail.value = null
    }

    /** Save (or clear, when blank) the user's video link for the open detail exercise. */
    fun saveVideoLink(exerciseId: String, url: String) {
        val trimmed = url.trim()
        _detail.value = _detail.value?.let {
            if (it.hit.id == exerciseId) it.copy(videoUrl = trimmed.ifBlank { null }) else it
        }
        viewModelScope.launch {
            if (trimmed.isBlank()) db.exerciseDao().deleteVideoLink(exerciseId)
            else db.exerciseDao().upsertVideoLink(
                dev.allan.workoutapp.data.db.ExerciseLink(exerciseId = exerciseId, url = trimmed)
            )
        }
    }

    /** Picker mode: append the exercise (with default sets) to the target workout. */
    fun addToWorkout(workoutId: Long, exerciseId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            dev.allan.workoutapp.data.PlanRepo.addExerciseToWorkout(db, workoutId, exerciseId)
            onDone()
            // Fetch the exercise image once, for offline use during sessions.
            dev.allan.workoutapp.data.MediaStore.ensureImage(getApplication(), db, exerciseId)
        }
    }

    fun createCustomExercise(
        name: String,
        description: String,
        primaryMuscleId: Int?,
        isCardio: Boolean,
        onDone: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val id = dev.allan.workoutapp.data.PlanRepo.createCustomExercise(
                db, name, description, primaryMuscleId, isCardio, appLang(),
            )
            onDone(id)
        }
    }
}
