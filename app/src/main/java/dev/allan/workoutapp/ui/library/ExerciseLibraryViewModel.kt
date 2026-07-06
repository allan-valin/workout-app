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
    /** exerciseId -> "alt names" preview line, filled when showAltNames is on. */
    val altNames: Map<String, String> = emptyMap(),
)

data class ExerciseDetail(
    val hit: ExerciseHit,
    val translations: List<ExerciseTranslation>,
)

class ExerciseLibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    private val _detail = MutableStateFlow<ExerciseDetail?>(null)
    val detail: StateFlow<ExerciseDetail?> = _detail

    val muscles: StateFlow<List<Muscle>> = db.exerciseDao().muscles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            val hits = db.exerciseDao().search(s.query.trim(), lang, muscleCsv)
            val alt = if (_state.value.showAltNames) buildAltNames(hits) else emptyMap()
            _state.value = _state.value.copy(
                searching = false, searched = true, results = hits, altNames = alt,
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
            _detail.value = ExerciseDetail(hit, db.exerciseDao().translations(hit.id))
        }
    }

    fun closeDetail() {
        _detail.value = null
    }
}
