package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.ActiveWorkoutDraftEntity
import com.example.data.db.AppConfigEntity
import com.example.data.db.NutritionEntryEntity
import com.example.data.db.PulseDatabase
import com.example.data.db.WorkoutSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: PulseDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pulse", appName)
    }

    @Test
    fun `room database - separates sample and real sessions and purges demo data`() = runBlocking {
        val workoutDao = db.workoutDao()

        val realSession = WorkoutSessionEntity(
            id = "user_session_1",
            title = "Real Chest Workout",
            routineId = null,
            startTimeMillis = 1000L,
            endTimeMillis = 2000L,
            durationMinutes = 45,
            notes = "Felt strong",
            totalVolumeKg = 2500f,
            isCompleted = true,
            isDemo = false
        )

        val demoSession = WorkoutSessionEntity(
            id = "demo_session_1",
            title = "Sample Full Body",
            routineId = null,
            startTimeMillis = 500L,
            endTimeMillis = 1500L,
            durationMinutes = 30,
            notes = "Sample demo data",
            totalVolumeKg = 1800f,
            isCompleted = true,
            isDemo = true
        )

        workoutDao.insertSession(realSession)
        workoutDao.insertSession(demoSession)

        // Verify total sessions is 2
        val allSessions = workoutDao.getAllSessions().first()
        assertEquals(2, allSessions.size)

        // Verify real sessions only returns 1
        val realOnly = workoutDao.getRealSessionsOnly().first()
        assertEquals(1, realOnly.size)
        assertEquals("user_session_1", realOnly[0].id)

        // Purge demo sessions
        workoutDao.purgeDemoSessions()

        // Real session must still exist
        val afterPurge = workoutDao.getAllSessions().first()
        assertEquals(1, afterPurge.size)
        assertEquals("user_session_1", afterPurge[0].id)
    }

    @Test
    fun `room database - active workout draft persistence and restoration`() = runBlocking {
        val workoutDao = db.workoutDao()

        val draft = ActiveWorkoutDraftEntity(
            id = "active_draft",
            title = "In Progress Push Day",
            routineId = "routine_starter_fullbody",
            startTimeMillis = System.currentTimeMillis() - 600000L,
            notes = "Draft notes",
            setsJson = """[{"setNumber":1,"exerciseId":"barbell_bench_press","exerciseName":"Bench Press","weightKg":80.0,"reps":8,"rpe":8,"isCompleted":true}]"""
        )

        workoutDao.saveActiveDraft(draft)

        val retrieved = workoutDao.getActiveDraft()
        assertNotNull(retrieved)
        assertEquals("In Progress Push Day", retrieved?.title)
        assertTrue(retrieved?.setsJson?.contains("barbell_bench_press") == true)

        workoutDao.clearActiveDraft()
        val afterClear = workoutDao.getActiveDraft()
        assertNull(afterClear)
    }

    @Test
    fun `room database - seed config flag prevents duplicate data seeding`() = runBlocking {
        val userDataDao = db.userDataDao()

        // Before seed
        val before = userDataDao.getConfig("seed_version")
        assertNull(before)

        // Mark seeded
        userDataDao.setConfig(AppConfigEntity("seed_version", "1"))

        // Check seed
        val after = userDataDao.getConfig("seed_version")
        assertNotNull(after)
        assertEquals("1", after?.value)
    }

    @Test
    fun `room database - nutrition entries demo flag and purge`() = runBlocking {
        val userDataDao = db.userDataDao()

        userDataDao.insertNutritionEntry(
            NutritionEntryEntity(
                dateMillis = System.currentTimeMillis(),
                mealType = "Breakfast",
                foodName = "Real Eggs & Toast",
                calories = 350,
                proteinGrams = 22,
                carbsGrams = 30,
                fatGrams = 14,
                isDemo = false
            )
        )
        userDataDao.insertNutritionEntry(
            NutritionEntryEntity(
                dateMillis = System.currentTimeMillis(),
                mealType = "Lunch",
                foodName = "Sample Meal",
                calories = 500,
                proteinGrams = 30,
                carbsGrams = 50,
                fatGrams = 10,
                isDemo = true
            )
        )

        assertEquals(2, userDataDao.getAllNutritionEntries().first().size)
        assertEquals(1, userDataDao.getRealNutritionEntries().first().size)

        userDataDao.purgeDemoNutrition()
        val remaining = userDataDao.getAllNutritionEntries().first()
        assertEquals(1, remaining.size)
        assertEquals("Real Eggs & Toast", remaining[0].foodName)
    }
}
