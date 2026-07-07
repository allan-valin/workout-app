package dev.allan.workoutapp

import dev.allan.workoutapp.data.SuggestionEngine
import dev.allan.workoutapp.data.SuggestionFocus
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private val knownMuscleIds = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    @Test
    fun everyFocusHasARecipe() {
        SuggestionFocus.entries.forEach { focus ->
            val recipe = SuggestionEngine.recipes[focus]
            assertTrue("missing recipe for $focus", recipe != null && recipe.isNotEmpty())
        }
    }

    @Test
    fun recipesUseKnownMusclesAndPositiveCounts() {
        SuggestionEngine.recipes.forEach { (focus, recipe) ->
            recipe.forEach { (muscleId, count) ->
                assertTrue(
                    "$focus references unknown muscle $muscleId",
                    muscleId == SuggestionEngine.CARDIO || muscleId in knownMuscleIds,
                )
                assertTrue("$focus has non-positive count for muscle $muscleId", count > 0)
            }
        }
    }

    @Test
    fun cardioOnlyInCardioCore() {
        SuggestionEngine.recipes.forEach { (focus, recipe) ->
            if (focus != SuggestionFocus.CARDIO_CORE) {
                assertTrue(
                    "$focus should not contain cardio",
                    recipe.none { it.first == SuggestionEngine.CARDIO },
                )
            }
        }
    }

    @Test
    fun recipesSuggestReasonableWorkoutSizes() {
        SuggestionEngine.recipes.forEach { (focus, recipe) ->
            val total = recipe.sumOf { it.second }
            assertTrue("$focus total $total out of 4..8", total in 4..8)
        }
    }
}
