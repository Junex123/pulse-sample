package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.PulseDatabase
import com.example.data.model.AiCoachMessage
import com.example.data.model.EnduranceSession
import com.example.data.model.Exercise
import com.example.data.model.ExtensionDescriptor
import com.example.data.model.HabitItem
import com.example.data.model.NutritionEntry
import com.example.data.model.PersonalRecord
import com.example.data.model.RecoveryEntry
import com.example.data.model.UserProfile
import com.example.data.model.WeightMeasurement
import com.example.data.model.WorkoutRoutine
import com.example.data.model.WorkoutSession
import com.example.data.model.WorkoutSet
import com.example.data.repository.AiCoachRepository
import com.example.data.repository.ExtensionItemUi
import com.example.data.repository.ExtensionRepository
import com.example.data.repository.UserDataRepository
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AppDestination {
    HOME,
    TRAINING,
    AI_COACH,
    EXPLORE,
    SETTINGS,
    EXTENSION_MANAGEMENT,
    EXERCISE_LIBRARY,
    ROUTINE_BUILDER,
    WORKOUT_HISTORY,
    ACTIVE_WORKOUT,
    // Extension specific full views
    NUTRITION_LAB,
    RECOVERY_STUDIO,
    HABIT_BUILDER,
    PROGRESS_ANALYTICS,
    ENDURANCE_LAB,
    ONBOARDING
}

class PulseViewModel(application: Application) : AndroidViewModel(application) {

    private val database = PulseDatabase.getInstance(application)
    private val workoutRepo = WorkoutRepository(database.workoutDao())
    private val extensionRepo = ExtensionRepository(database.extensionDao())
    private val userDataRepo = UserDataRepository(database.userDataDao())
    private val aiCoachRepo = AiCoachRepository()

    // Loading readiness gate
    private val _isAppReady = MutableStateFlow(false)
    val isAppReady: StateFlow<Boolean> = _isAppReady.asStateFlow()

    // Navigation Backstack
    private val _currentScreen = MutableStateFlow(AppDestination.HOME)
    val currentScreen: StateFlow<AppDestination> = _currentScreen.asStateFlow()

    private val navStack = mutableListOf<AppDestination>()

    fun navigateTo(destination: AppDestination) {
        if (_currentScreen.value != destination) {
            navStack.add(_currentScreen.value)
            _currentScreen.value = destination
        }
    }

    fun navigateBack(): Boolean {
        if (navStack.isNotEmpty()) {
            _currentScreen.value = navStack.removeAt(navStack.size - 1)
            return true
        } else if (_currentScreen.value != AppDestination.HOME) {
            _currentScreen.value = AppDestination.HOME
            return true
        }
        return false
    }

    // Initialization
    init {
        viewModelScope.launch {
            workoutRepo.initializeDefaultRoutinesIfEmpty()
            workoutRepo.seedDemoWorkoutHistoryIfEmpty()
            extensionRepo.initializeDefaultStatesIfEmpty()
            userDataRepo.initializeDefaultDataIfEmpty()

            // Check onboarding gate
            val profile = userDataRepo.userProfile.first()
            if (!profile.hasCompletedOnboarding) {
                _currentScreen.value = AppDestination.ONBOARDING
            }
            _isAppReady.value = true
        }
    }

    // User Profile
    val userProfile: StateFlow<UserProfile> = userDataRepo.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserProfile())

    fun updateProfile(profile: UserProfile) {
        viewModelScope.launch {
            userDataRepo.saveUserProfile(profile)
        }
    }

    fun toggleDarkMode(isDark: Boolean) {
        viewModelScope.launch {
            val curr = userProfile.value
            userDataRepo.saveUserProfile(curr.copy(isDarkMode = isDark))
        }
    }

    fun toggleDemoData(enabled: Boolean) {
        viewModelScope.launch {
            val curr = userProfile.value
            userDataRepo.saveUserProfile(curr.copy(isDemoDataEnabled = enabled))
        }
    }

    fun toggleAiConsent(granted: Boolean) {
        viewModelScope.launch {
            val curr = userProfile.value
            userDataRepo.saveUserProfile(curr.copy(isAiConsentGranted = granted))
        }
    }

    fun setDashboardCards(cards: List<String>) {
        viewModelScope.launch {
            val curr = userProfile.value
            userDataRepo.saveUserProfile(curr.copy(visibleDashboardCards = cards))
        }
    }

    fun completeOnboarding(profile: UserProfile) {
        viewModelScope.launch {
            userDataRepo.saveUserProfile(profile.copy(hasCompletedOnboarding = true))
            _currentScreen.value = AppDestination.HOME
        }
    }

    // Workouts & Exercises
    val allExercises: List<Exercise> = workoutRepo.allExercises

    val allRoutines: StateFlow<List<WorkoutRoutine>> = workoutRepo.allRoutines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSessions: StateFlow<List<WorkoutSession>> = workoutRepo.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val personalRecords: StateFlow<List<PersonalRecord>> = workoutRepo.personalRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Workout Draft (Restoration & Persistence)
    val activeDraft: StateFlow<WorkoutSession?> = workoutRepo.activeDraft
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _activeSession = MutableStateFlow<WorkoutSession?>(null)
    val activeSession: StateFlow<WorkoutSession?> = _activeSession.asStateFlow()

    private var sessionTimerJob: Job? = null
    private val _sessionElapsedSeconds = MutableStateFlow(0)
    val sessionElapsedSeconds: StateFlow<Int> = _sessionElapsedSeconds.asStateFlow()

    // Rest Timer with timestamp drift-protection
    private val _isRestTimerActive = MutableStateFlow(false)
    val isRestTimerActive: StateFlow<Boolean> = _isRestTimerActive.asStateFlow()

    private val _restTimerRemainingSeconds = MutableStateFlow(0)
    val restTimerRemainingSeconds: StateFlow<Int> = _restTimerRemainingSeconds.asStateFlow()

    private val _restTimerTotalSeconds = MutableStateFlow(60)
    val restTimerTotalSeconds: StateFlow<Int> = _restTimerTotalSeconds.asStateFlow()

    private var restTimerJob: Job? = null
    private var restTimerEndTimestamp: Long = 0L

    fun startWorkout(routine: WorkoutRoutine? = null) {
        val initialSets = mutableListOf<WorkoutSet>()
        val routineExercises = routine?.exerciseIds?.mapNotNull { id ->
            workoutRepo.getExerciseById(id)
        } ?: listOf(allExercises.first())

        routineExercises.forEach { ex ->
            for (i in 1..3) {
                initialSets.add(
                    WorkoutSet(
                        setNumber = i,
                        exerciseId = ex.id,
                        exerciseName = ex.name,
                        weightKg = if (ex.equipment == "Bodyweight") 0f else 20f,
                        reps = 10,
                        rpe = 8,
                        isCompleted = false
                    )
                )
            }
        }

        val now = System.currentTimeMillis()
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            title = routine?.name ?: "Quick Workout Session",
            routineId = routine?.id,
            startTimeMillis = now,
            sets = initialSets,
            isCompleted = false
        )

        _activeSession.value = session
        _sessionElapsedSeconds.value = 0

        startDriftProofSessionTimer(now)

        // Persist draft immediately
        viewModelScope.launch {
            workoutRepo.saveActiveDraft(session)
        }

        navigateTo(AppDestination.ACTIVE_WORKOUT)
    }

    fun resumeDraft(draft: WorkoutSession) {
        _activeSession.value = draft
        val elapsed = ((System.currentTimeMillis() - draft.startTimeMillis) / 1000).toInt().coerceAtLeast(0)
        _sessionElapsedSeconds.value = elapsed

        startDriftProofSessionTimer(draft.startTimeMillis)
        navigateTo(AppDestination.ACTIVE_WORKOUT)
    }

    fun discardDraft() {
        viewModelScope.launch {
            workoutRepo.clearActiveDraft()
        }
    }

    private fun startDriftProofSessionTimer(startTimeMillis: Long) {
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val elapsed = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt().coerceAtLeast(0)
                _sessionElapsedSeconds.value = elapsed
            }
        }
    }

    fun addExerciseToActiveWorkout(exercise: Exercise) {
        val current = _activeSession.value ?: return
        val currentSets = current.sets.toMutableList()
        currentSets.add(
            WorkoutSet(
                setNumber = 1,
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                weightKg = if (exercise.equipment == "Bodyweight") 0f else 20f,
                reps = 10,
                rpe = 8,
                isCompleted = false
            )
        )
        val updated = current.copy(sets = currentSets)
        _activeSession.value = updated
        persistActiveDraft(updated)
    }

    fun addSetToExercise(exerciseId: String, exerciseName: String) {
        val current = _activeSession.value ?: return
        val existingSetsForEx = current.sets.filter { it.exerciseId == exerciseId }
        val nextSetNumber = existingSetsForEx.size + 1
        val lastSet = existingSetsForEx.lastOrNull()

        val newSet = WorkoutSet(
            setNumber = nextSetNumber,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            weightKg = lastSet?.weightKg ?: 20f,
            reps = lastSet?.reps ?: 10,
            rpe = lastSet?.rpe ?: 8,
            isCompleted = false
        )
        val updated = current.copy(sets = current.sets + newSet)
        _activeSession.value = updated
        persistActiveDraft(updated)
    }

    fun updateSet(index: Int, weightKg: Float, reps: Int, rpe: Int) {
        val current = _activeSession.value ?: return
        if (index in current.sets.indices) {
            val updatedSets = current.sets.toMutableList()
            updatedSets[index] = updatedSets[index].copy(weightKg = weightKg, reps = reps, rpe = rpe)
            val updated = current.copy(sets = updatedSets)
            _activeSession.value = updated
            persistActiveDraft(updated)
        }
    }

    fun toggleSetCompleted(index: Int) {
        val current = _activeSession.value ?: return
        if (index in current.sets.indices) {
            val updatedSets = current.sets.toMutableList()
            val wasCompleted = updatedSets[index].isCompleted
            val willBeCompleted = !wasCompleted
            updatedSets[index] = updatedSets[index].copy(isCompleted = willBeCompleted)
            val updated = current.copy(sets = updatedSets)
            _activeSession.value = updated
            persistActiveDraft(updated)

            if (willBeCompleted) {
                startRestTimer(_restTimerTotalSeconds.value)
            }
        }
    }

    private fun persistActiveDraft(session: WorkoutSession) {
        viewModelScope.launch {
            workoutRepo.saveActiveDraft(session)
        }
    }

    fun startRestTimer(durationSeconds: Int) {
        restTimerJob?.cancel()
        _restTimerTotalSeconds.value = durationSeconds
        _restTimerRemainingSeconds.value = durationSeconds
        _isRestTimerActive.value = true
        restTimerEndTimestamp = System.currentTimeMillis() + (durationSeconds * 1000L)

        restTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = ((restTimerEndTimestamp - System.currentTimeMillis() + 999) / 1000).toInt()
                if (remaining <= 0) {
                    _restTimerRemainingSeconds.value = 0
                    _isRestTimerActive.value = false
                    break
                }
                _restTimerRemainingSeconds.value = remaining
                delay(500L)
            }
        }
    }

    fun pauseOrDismissRestTimer() {
        restTimerJob?.cancel()
        _isRestTimerActive.value = false
    }

    fun finishWorkout(notes: String = "") {
        val current = _activeSession.value ?: return
        val durationMins = maxOf(1, _sessionElapsedSeconds.value / 60)
        val endTime = System.currentTimeMillis()

        sessionTimerJob?.cancel()
        pauseOrDismissRestTimer()

        viewModelScope.launch {
            workoutRepo.saveCompletedWorkout(
                title = current.title,
                routineId = current.routineId,
                startTimeMillis = current.startTimeMillis,
                endTimeMillis = endTime,
                durationMinutes = durationMins,
                notes = notes,
                sets = current.sets
            )
            workoutRepo.clearActiveDraft()
            _activeSession.value = null
            _sessionElapsedSeconds.value = 0
            _currentScreen.value = AppDestination.TRAINING
        }
    }

    fun cancelActiveWorkout() {
        sessionTimerJob?.cancel()
        pauseOrDismissRestTimer()
        _activeSession.value = null
        _sessionElapsedSeconds.value = 0
        viewModelScope.launch {
            workoutRepo.clearActiveDraft()
        }
        navigateBack()
    }

    fun saveCustomRoutine(name: String, description: String, category: String, exerciseIds: List<String>, estimatedMinutes: Int) {
        viewModelScope.launch {
            workoutRepo.saveCustomRoutine(name, description, category, exerciseIds, estimatedMinutes)
        }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch {
            workoutRepo.deleteRoutine(routineId)
        }
    }

    // Extensions Marketplace
    val allExtensions: StateFlow<List<ExtensionItemUi>> = extensionRepo.allExtensions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enabledExtensions: StateFlow<List<ExtensionItemUi>> = extensionRepo.allExtensions
        .combine(MutableStateFlow(Unit)) { exts, _ ->
            exts.filter { it.isInstalled && it.isEnabled }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleExtensionEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            extensionRepo.setExtensionEnabled(id, enabled)
        }
    }

    fun installExtension(id: String) {
        viewModelScope.launch {
            extensionRepo.installExtension(id)
        }
    }

    fun uninstallExtension(id: String) {
        viewModelScope.launch {
            extensionRepo.uninstallExtension(id)
        }
    }

    // Extension Data Flows
    val nutritionEntries: StateFlow<List<NutritionEntry>> = userDataRepo.nutritionEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun logNutrition(foodName: String, mealType: String, calories: Int, protein: Int, carbs: Int, fat: Int) {
        viewModelScope.launch {
            userDataRepo.logNutrition(
                NutritionEntry(
                    mealType = mealType,
                    foodName = foodName,
                    calories = calories,
                    proteinGrams = protein,
                    carbsGrams = carbs,
                    fatGrams = fat
                )
            )
        }
    }

    fun deleteNutrition(id: Long) {
        viewModelScope.launch {
            userDataRepo.deleteNutrition(id)
        }
    }

    val recoveryEntries: StateFlow<List<RecoveryEntry>> = userDataRepo.recoveryEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun logRecovery(sleepHours: Float, quality: Int, soreness: Int, score: Int, notes: String) {
        viewModelScope.launch {
            userDataRepo.logRecovery(
                RecoveryEntry(
                    dateIso = "Today",
                    sleepHours = sleepHours,
                    sleepQuality = quality,
                    sorenessLevel = soreness,
                    readinessScore = score,
                    notes = notes
                )
            )
        }
    }

    val habits: StateFlow<List<HabitItem>> = userDataRepo.habits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleHabit(habit: HabitItem) {
        viewModelScope.launch {
            userDataRepo.toggleHabit(habit.id)
        }
    }

    fun addHabit(title: String, targetCount: Int) {
        viewModelScope.launch {
            userDataRepo.addHabit(title, targetCount)
        }
    }

    val weightMeasurements: StateFlow<List<WeightMeasurement>> = userDataRepo.weightMeasurements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun logWeight(weightKg: Float, notes: String = "") {
        viewModelScope.launch {
            userDataRepo.logWeight(weightKg, notes)
            val curr = userProfile.value
            userDataRepo.saveUserProfile(curr.copy(weightKg = weightKg))
        }
    }

    val enduranceSessions: StateFlow<List<EnduranceSession>> = userDataRepo.enduranceSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun logEndurance(activity: String, distanceKm: Float, durationMinutes: Int, calories: Int) {
        viewModelScope.launch {
            userDataRepo.logEndurance(
                EnduranceSession(
                    dateMillis = System.currentTimeMillis(),
                    activityType = activity,
                    distanceKm = distanceKm,
                    durationMinutes = durationMinutes,
                    caloriesBurned = calories
                )
            )
        }
    }

    // AI Coach Chat
    val coachMessages: StateFlow<List<AiCoachMessage>> = aiCoachRepo.messages
    val isCoachGenerating: StateFlow<Boolean> = aiCoachRepo.isGenerating

    fun sendCoachMessage(prompt: String) {
        viewModelScope.launch {
            val profile = userProfile.value
            val recentSessions = allSessions.value.take(3)
            val summary = if (recentSessions.isEmpty()) {
                "No completed sessions logged yet."
            } else {
                recentSessions.joinToString("; ") { "${it.title} (${it.durationMinutes} min, ${it.sets.size} sets, ${it.totalVolumeKg.toInt()} kg volume)" }
            }
            aiCoachRepo.sendMessage(prompt, profile, summary)
        }
    }

    // Data Management & Security Features
    fun purgeDemoData() {
        viewModelScope.launch {
            workoutRepo.purgeDemoData()
            userDataRepo.purgeDemoData()
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            userDataRepo.deleteAllUserData()
            workoutRepo.clearActiveDraft()
            _activeSession.value = null
            _currentScreen.value = AppDestination.ONBOARDING
        }
    }

    fun exportAllDataAsJson(): String {
        val root = JSONObject()
        root.put("exportTimestamp", System.currentTimeMillis())
        root.put("app", "Pulse Health Platform")
        root.put("version", "1.0.0")

        val prof = userProfile.value
        val profileJson = JSONObject().apply {
            put("name", prof.name)
            put("goal", prof.goal)
            put("experienceLevel", prof.experienceLevel)
            put("weightKg", prof.weightKg)
            put("heightCm", prof.heightCm)
            put("weightUnit", prof.weightUnit)
            put("heightUnit", prof.heightUnit)
        }
        root.put("userProfile", profileJson)

        val routinesArr = JSONArray()
        allRoutines.value.forEach { r ->
            routinesArr.put(JSONObject().apply {
                put("name", r.name)
                put("category", r.category)
                put("estimatedMinutes", r.estimatedMinutes)
                put("exerciseCount", r.exerciseIds.size)
            })
        }
        root.put("routines", routinesArr)

        val sessionsArr = JSONArray()
        allSessions.value.forEach { s ->
            sessionsArr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("startTimeMillis", s.startTimeMillis)
                put("durationMinutes", s.durationMinutes)
                put("totalVolumeKg", s.totalVolumeKg)
                put("isDemo", s.isDemo)
                put("setCount", s.sets.size)
            })
        }
        root.put("sessions", sessionsArr)

        val prArr = JSONArray()
        personalRecords.value.forEach { pr ->
            prArr.put(JSONObject().apply {
                put("exerciseName", pr.exerciseName)
                put("maxWeightKg", pr.maxWeightKg)
                put("maxReps", pr.maxReps)
                put("estimatedOneRepMaxKg", pr.estimatedOneRepMaxKg)
            })
        }
        root.put("personalRecords", prArr)

        val nutritionArr = JSONArray()
        nutritionEntries.value.forEach { n ->
            nutritionArr.put(JSONObject().apply {
                put("mealType", n.mealType)
                put("foodName", n.foodName)
                put("calories", n.calories)
                put("proteinGrams", n.proteinGrams)
                put("carbsGrams", n.carbsGrams)
                put("fatGrams", n.fatGrams)
                put("isDemo", n.isDemo)
            })
        }
        root.put("nutritionEntries", nutritionArr)

        val recoveryArr = JSONArray()
        recoveryEntries.value.forEach { rec ->
            recoveryArr.put(JSONObject().apply {
                put("sleepHours", rec.sleepHours)
                put("sleepQuality", rec.sleepQuality)
                put("sorenessLevel", rec.sorenessLevel)
                put("readinessScore", rec.readinessScore)
                put("isDemo", rec.isDemo)
            })
        }
        root.put("recoveryEntries", recoveryArr)

        val habitsArr = JSONArray()
        habits.value.forEach { h ->
            habitsArr.put(JSONObject().apply {
                put("title", h.title)
                put("targetCount", h.targetCount)
                put("streakDays", h.streakDays)
                put("isCompletedToday", h.isCompletedToday)
            })
        }
        root.put("habits", habitsArr)

        val weightArr = JSONArray()
        weightMeasurements.value.forEach { w ->
            weightArr.put(JSONObject().apply {
                put("dateMillis", w.dateMillis)
                put("weightKg", w.weightKg)
                put("notes", w.notes)
                put("isDemo", w.isDemo)
            })
        }
        root.put("weightMeasurements", weightArr)

        return root.toString(2)
    }
}
