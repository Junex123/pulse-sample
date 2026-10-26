package com.example.data.repository

import com.example.data.model.AiCoachMessage
import com.example.data.model.MessageSender
import com.example.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AiCoachRequest(
    val prompt: String,
    val goal: String,
    val experienceLevel: String,
    val recentActivitySummary: String
)

data class AiCoachResponse(
    val replyText: String,
    val followUpPrompts: List<String> = emptyList()
)

class AiCoachRepository {

    // Configurable backend endpoint for secure server-side AI proxy.
    // Keeps privileged API keys completely off the client APK.
    var backendEndpointUrl: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _messages = MutableStateFlow<List<AiCoachMessage>>(emptyList())
    val messages: StateFlow<List<AiCoachMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Rate limiting
    private var lastRequestTimestamp = 0L
    private val requestTimestampsInLastHour = mutableListOf<Long>()
    private val MAX_REQUESTS_PER_HOUR = 30
    private val MIN_INTERVAL_MILLIS = 1500L

    init {
        _messages.value = listOf(
            AiCoachMessage(
                id = "welcome_msg",
                sender = MessageSender.COACH,
                text = "Hello! I'm your Pulse AI Coach. I'm here to help you structure workouts, understand progressive overload, choose exercise substitutions, and optimize recovery tailored to your goals.\n\n*Notice: AI fitness guidance is strictly educational and does not constitute medical advice or diagnosis.*",
                quickPrompts = listOf(
                    "How to structure my workout week?",
                    "Best exercises for chest hypertrophy?",
                    "How much protein do I need?",
                    "What to do when my muscles are sore?"
                )
            )
        )
    }

    suspend fun sendMessage(userText: String, profile: UserProfile, recentWorkoutsSummary: String) {
        val userMsg = AiCoachMessage(
            id = UUID.randomUUID().toString(),
            sender = MessageSender.USER,
            text = userText
        )
        _messages.value = _messages.value + userMsg

        // Check informed AI consent
        if (!profile.isAiConsentGranted && backendEndpointUrl.isNotBlank()) {
            val consentNotice = AiCoachMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.COACH,
                text = "Privacy Notice: To protect your personal health information, external AI processing requires explicit consent. You can grant AI consent in Settings, or use our secure on-device coaching intelligence below.",
                quickPrompts = listOf("Explain progressive overload", "Chest exercises", "How much sleep do I need?")
            )
            _messages.value = _messages.value + consentNotice
            return
        }

        // Check client-side rate limits
        val now = System.currentTimeMillis()
        if (now - lastRequestTimestamp < MIN_INTERVAL_MILLIS) {
            _messages.value = _messages.value + AiCoachMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.COACH,
                text = "Please pause briefly between questions."
            )
            return
        }

        requestTimestampsInLastHour.removeAll { now - it > 3600000L }
        if (requestTimestampsInLastHour.size >= MAX_REQUESTS_PER_HOUR) {
            _messages.value = _messages.value + AiCoachMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.COACH,
                text = "Hourly coaching quota reached. Please check back in a little while!"
            )
            return
        }

        lastRequestTimestamp = now
        requestTimestampsInLastHour.add(now)

        _isGenerating.value = true

        val coachResponse = withContext(Dispatchers.IO) {
            if (backendEndpointUrl.isNotBlank() && profile.isAiConsentGranted) {
                callBackendAiProxy(userText, profile, recentWorkoutsSummary)
            } else {
                generateBuiltInCoachResponse(userText, profile)
            }
        }

        _isGenerating.value = false
        _messages.value = _messages.value + coachResponse
    }

    private fun callBackendAiProxy(
        userText: String,
        profile: UserProfile,
        recentWorkoutsSummary: String
    ): AiCoachMessage {
        return try {
            val sanitizedRequest = JSONObject().apply {
                put("prompt", userText.take(500))
                put("goal", profile.goal)
                put("experienceLevel", profile.experienceLevel)
                put("preferredFrequencyDays", profile.preferredFrequencyDays)
                put("recentActivity", recentWorkoutsSummary.take(300))
            }

            val request = Request.Builder()
                .url(backendEndpointUrl)
                .post(sanitizedRequest.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return generateBuiltInCoachResponse(userText, profile, isOfflineFallback = true)
            }

            val json = JSONObject(responseBody)
            val reply = json.optString("reply", "")
            if (reply.isBlank()) {
                generateBuiltInCoachResponse(userText, profile, isOfflineFallback = true)
            } else {
                AiCoachMessage(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.COACH,
                    text = reply.trim(),
                    quickPrompts = getFollowUpPrompts(userText)
                )
            }
        } catch (e: Exception) {
            generateBuiltInCoachResponse(userText, profile, isOfflineFallback = true)
        }
    }

    private fun generateBuiltInCoachResponse(
        userText: String,
        profile: UserProfile,
        isOfflineFallback: Boolean = false
    ): AiCoachMessage {
        val lower = userText.lowercase()
        val notice = if (isOfflineFallback) "*(Network notice: Using secure on-device coaching knowledge)*\n\n" else ""

        val advice = when {
            "structure" in lower || "week" in lower || "split" in lower -> {
                """
                Recommended training split for **${profile.goal}** (${profile.preferredFrequencyDays} days/week):

                • **Day 1**: Upper Body (Chest bench press, Horizontal row, Tricep pushdowns)
                • **Day 2**: Lower Body (Squats, Romanian deadlifts, Calves & Core)
                • **Day 3**: Active Recovery (Light 20-min mobility or brisk walk)
                • **Day 4**: Upper Body (Overhead press, Lat pulldown, Biceps)
                • **Day 5**: Lower Body & Core (Goblet squats, Hamstring hinge, Planks)
                • **Days 6-7**: Rest & high-protein recovery

                *Principle: Allow 48 hours of recovery before retraining the same muscle group heavily.*
                """.trimIndent()
            }
            "chest" in lower || "bench" in lower || "pec" in lower -> {
                """
                Key exercises for chest strength & hypertrophy:

                1. **Barbell or Dumbbell Bench Press**: 3-4 sets of 6-10 reps. Lower bar smoothly with elbows tucked ~45° to protect rotator cuffs.
                2. **Incline Dumbbell Press (30° angle)**: 3 sets of 8-12 reps. Emphasizes the clavicular head (upper chest).
                3. **Push-ups or Cable Flyes**: 2-3 sets to near failure (RPE 9) for metabolic stimulus.

                *Form Cue: Keep shoulder blades pinched together and driven downward against the bench.*
                """.trimIndent()
            }
            "protein" in lower || "diet" in lower || "macro" in lower || "nutrition" in lower -> {
                val weight = profile.weightKg
                val proteinMin = (weight * 1.6f).toInt()
                val proteinMax = (weight * 2.2f).toInt()
                """
                Protein targets for **${profile.goal}**:

                • Target: **1.6g – 2.2g per kg of body weight** daily.
                • For your weight of **${weight} kg**, aim for roughly **${proteinMin}g – ${proteinMax}g of protein daily**.
                • Distribute across 3-4 meals (~30-45g per meal) to optimize muscle protein synthesis.
                • Quality sources: Chicken breast, eggs, paneer, Greek yogurt, whey protein, dal, lentils, and fish.
                """.trimIndent()
            }
            "sore" in lower || "recovery" in lower || "pain" in lower || "rest" in lower -> {
                """
                Delayed Onset Muscle Soreness (DOMS) recovery cues:

                • **Active Recovery**: A 20-minute light walk or easy cycling increases blood flow without adding fatigue.
                • **Sleep Priority**: 7-9 hours of deep sleep is essential for muscle tissue repair.
                • **Hydration**: Maintain steady electrolyte and water intake (2.5L - 3L daily).
                • **Safety**: If pain is sharp, localized to a tendon or joint, rest and consult a healthcare professional.
                """.trimIndent()
            }
            "substitute" in lower || "alternative" in lower || "replace" in lower -> {
                """
                Smart compound exercise substitutions:

                • **Barbell Bench Press** ➔ Dumbbell Press, Floor Press, or Weighted Push-ups
                • **Barbell Back Squat** ➔ Dumbbell Goblet Squat, Bulgarian Split Squat, or Leg Press
                • **Barbell Deadlift** ➔ Romanian Deadlift, Trap Bar Deadlift, or Dumbbell RDL
                • **Pull-ups** ➔ Lat Pulldowns, Inverted Rows, or Resistance Band Assisted Pull-ups
                • **Overhead Press** ➔ Seated Dumbbell Shoulder Press or Pike Push-ups
                """.trimIndent()
            }
            "rpe" in lower || "rate of perceived" in lower || "effort" in lower -> {
                """
                The RPE (Rate of Perceived Exertion) Scale (1-10):

                • **RPE 10**: Maximum effort, 0 reps left in the tank.
                • **RPE 9**: Heavy, exactly 1 rep left in reserve.
                • **RPE 8**: Solid working weight, 2 reps left in reserve (ideal for progressive overload).
                • **RPE 7**: Moderate, 3 reps left (great for volume and warmups).

                *Rule: Train most hypertrophy sets around RPE 7-8 to stimulate growth without excessive neurological fatigue.*
                """.trimIndent()
            }
            else -> {
                """
                Guidance tailored to your goal of **${profile.goal}** (${profile.experienceLevel} level):

                • **Progressive Overload**: Strive to add 1 rep or small weight increments (1-2.5 kg) to compound lifts each week.
                • **Consistent Execution**: 3-4 structured sessions per week yields superior long-term results over sporadic bursts.
                • **Rest Intervals**: Rest 90-120 seconds between compound movements to let ATP stores regenerate.

                What specific movement, routine question, or recovery topic would you like to explore?
                """.trimIndent()
            }
        }

        return AiCoachMessage(
            id = UUID.randomUUID().toString(),
            sender = MessageSender.COACH,
            text = notice + advice,
            quickPrompts = getFollowUpPrompts(userText)
        )
    }

    private fun getFollowUpPrompts(userText: String): List<String> {
        val lower = userText.lowercase()
        return when {
            "chest" in lower || "bench" in lower -> listOf(
                "Substitutions for bench press",
                "How long to rest between heavy sets?",
                "How to warm up shoulders safely?"
            )
            "protein" in lower -> listOf(
                "Best pre-workout meal timing?",
                "Creatine dosage & benefits?",
                "How to track macros in Nutrition Lab?"
            )
            else -> listOf(
                "How to track progressive overload?",
                "Explain RPE scale (1-10)",
                "How much sleep is needed for recovery?"
            )
        }
    }

    fun clearHistory() {
        _messages.value = listOf(
            AiCoachMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.COACH,
                text = "Conversation history cleared. How can I assist your training today?",
                quickPrompts = listOf(
                    "How to structure my workout week?",
                    "Best exercises for chest hypertrophy?",
                    "How much protein do I need?"
                )
            )
        )
    }
}
