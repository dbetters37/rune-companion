package com.aicontrolcenter.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.testkit.contract.SettingsRepositoryContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opens settings stores over real files and releases them after each test. */
class SettingsStores {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val jobs = mutableListOf<Job>()
    private val nextFile = AtomicInteger()

    fun newFileName(): String = "settings-test-${nextFile.incrementAndGet()}"

    fun open(fileName: String): Pair<DataStore<Preferences>, Job> {
        val job = SupervisorJob().also(jobs::add)
        return DataStoreSettingsRepository.createStore(context, CoroutineScope(job + Dispatchers.IO), fileName) to job
    }

    fun repository(fileName: String = newFileName()): DataStoreSettingsRepository =
        DataStoreSettingsRepository(open(fileName).first)

    fun closeAll() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataStoreSettingsRepositoryContractTest : SettingsRepositoryContract() {
    private val stores = SettingsStores()

    override fun createRepository(): SettingsRepository = stores.repository()

    @After
    fun tearDown() = stores.closeAll()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataStoreSettingsRepositoryTest {
    private val stores = SettingsStores()

    @After
    fun tearDown() = stores.closeAll()

    private val customised = FrameworkSettings(
        localOnlyMode = true, activityRetentionDays = 0, directorAgentId = AgentId("agt_director"),
        maxConcurrentTasks = 5, operatorEnabled = true, onboardingCompleted = true, seedVersion = 2,
    )

    @Test
    fun `settings survive closing and reopening the store`() = runTest {
        val file = stores.newFileName()
        val (firstStore, firstJob) = stores.open(file)
        DataStoreSettingsRepository(firstStore).update { customised }
        firstJob.cancelAndJoin()

        assertEquals(customised, stores.repository(file).get())
    }

    @Test
    fun `the public factory creates a working repository over the named file`() = runTest {
        val job = SupervisorJob()
        try {
            val repository = DataStoreSettingsRepository.create(stores.context, CoroutineScope(job + Dispatchers.IO), "factory-test")
            repository.update { it.copy(seedVersion = 9) }
            assertEquals(9, repository.get().seedVersion)
            assertTrue(stores.context.preferencesDataStoreFile("factory-test").isFile)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `observers receive each change once`() = runTest {
        val repository = stores.repository()
        repository.settings.test {
            assertEquals(FrameworkSettings(), awaitItem())
            repository.update { it.copy(localOnlyMode = true) }
            assertEquals(FrameworkSettings(localOnlyMode = true), awaitItem())
            // An update that changes nothing must not wake observers.
            repository.update { it }
            repository.update { it.copy(maxConcurrentTasks = 7) }
            assertEquals(FrameworkSettings(localOnlyMode = true, maxConcurrentTasks = 7), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent updates are all applied`() = runTest {
        val repository = stores.repository()
        val updates = 100
        coroutineScope {
            repeat(updates) {
                launch(Dispatchers.Default) {
                    repository.update { it.copy(maxTasksPerObjective = it.maxTasksPerObjective + 1) }
                }
            }
        }
        assertEquals(FrameworkSettings().maxTasksPerObjective + updates, repository.get().maxTasksPerObjective)
    }

    @Test
    fun `a failing transform writes nothing and reaches the caller`() = runTest {
        val repository = stores.repository()
        repository.update { customised }
        assertFailsWith<IllegalArgumentException> { repository.update { throw IllegalArgumentException("rejected") } }
        assertEquals(customised, repository.get())
    }

    @Test
    fun `one unreadable field takes its default while every other setting keeps its value`() = runTest {
        val stored = """{"localOnlyMode":true,"maxConcurrentTasks":"many","directorAgentId":"agt_director",""" +
            """"operatorEnabled":true,"onboardingCompleted":true,"seedVersion":3,"activityRetentionDays":7}"""
        val (store, _) = stores.open(stores.newFileName())
        store.edit { it[DataStoreSettingsRepository.SETTINGS_KEY] = stored }
        val repository = DataStoreSettingsRepository(store)

        val expected = FrameworkSettings(
            localOnlyMode = true, directorAgentId = AgentId("agt_director"), operatorEnabled = true,
            onboardingCompleted = true, seedVersion = 3, activityRetentionDays = 7,
        )
        assertEquals(expected, repository.get())
        assertEquals(expected, repository.settings.first())
        // Reading alone changes nothing on disk.
        assertEquals(stored, store.data.first()[DataStoreSettingsRepository.SETTINGS_KEY])
        assertNull(store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY])
    }

    @Test
    fun `the original text of an incompletely readable value is kept when an update overwrites it`() = runTest {
        val file = stores.newFileName()
        val (store, job) = stores.open(file)
        val stored = """{"localOnlyMode":true,"maxConcurrentTasks":"many","seedVersion":3}"""
        store.edit { it[DataStoreSettingsRepository.SETTINGS_KEY] = stored }
        val repository = DataStoreSettingsRepository(store)

        // A transform that fails writes nothing, not even the copy.
        assertFailsWith<IllegalArgumentException> { repository.update { throw IllegalArgumentException("rejected") } }
        assertNull(store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY])

        val updated = repository.update { it.copy(seedVersion = 4) }
        assertEquals(FrameworkSettings(localOnlyMode = true, seedVersion = 4), updated)
        assertEquals(stored, store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY])

        // The value is whole again, so later updates leave the copy alone.
        repository.update { it.copy(seedVersion = 5) }
        assertEquals(stored, store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY])
        job.cancelAndJoin()
        assertEquals(FrameworkSettings(localOnlyMode = true, seedVersion = 5), stores.repository(file).get())
    }

    @Test
    fun `updating readable settings keeps no copy`() = runTest {
        val (store, _) = stores.open(stores.newFileName())
        val repository = DataStoreSettingsRepository(store)
        repository.update { customised }
        repository.update { it.copy(seedVersion = 9) }
        assertNull(store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY])
    }

    @Test
    fun `a stored value that is not settings JSON at all gives defaults with local-only mode on, and is kept aside`() = runTest {
        val afterLoss = FrameworkSettings(localOnlyMode = true)
        for (damaged in listOf("{\"localOnlyMode\": tru", "not json at all", "", "[1,2,3]")) {
            val file = stores.newFileName()
            val (store, job) = stores.open(file)
            store.edit { it[DataStoreSettingsRepository.SETTINGS_KEY] = damaged }
            val repository = DataStoreSettingsRepository(store)

            assertEquals(afterLoss, repository.get(), "get() for stored value '$damaged'")
            assertEquals(afterLoss, repository.settings.first(), "settings for stored value '$damaged'")

            val repaired = repository.update { it.copy(seedVersion = 4) }
            assertEquals(afterLoss.copy(seedVersion = 4), repaired)
            assertEquals(damaged, store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY])
            job.cancelAndJoin()
            assertEquals(afterLoss.copy(seedVersion = 4), stores.repository(file).get(), "after reopening '$damaged'")
        }
    }

    @Test
    fun `a settings file that is not a preferences file at all is reset to defaults with local-only mode on`() = runTest {
        val garbage = ByteArray(64) { 0xFF.toByte() }
        fun damagedFile() = stores.newFileName().also { name ->
            stores.context.preferencesDataStoreFile(name).apply {
                parentFile!!.mkdirs()
                writeBytes(garbage)
            }
        }

        // DataStore on its own refuses such a file; the repository's corruption handler is what recovers.
        val plainJob = SupervisorJob()
        val plainFile = damagedFile()
        val plainStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(plainJob + Dispatchers.IO),
            produceFile = { stores.context.preferencesDataStoreFile(plainFile) },
        )
        assertFailsWith<CorruptionException> { plainStore.data.first() }
        plainJob.cancelAndJoin()

        val repository = stores.repository(damagedFile())

        // What the owner had chosen is unknown, so the privacy switch comes back on.
        assertEquals(FrameworkSettings(localOnlyMode = true), repository.get())
        assertEquals(FrameworkSettings(localOnlyMode = true), repository.settings.first())
        assertEquals(customised, repository.update { customised })
        assertEquals(customised, repository.get())
    }

    @Test
    fun `settings written by release 0_2_0 load with the memory seed not yet applied, and recording it keeps no copy`() = runTest {
        val (store, _) = stores.open(stores.newFileName())
        val written = SettingsCodec.encode(customised).replace(""","memorySeedVersion":0""", "")
        assertTrue("memorySeedVersion" !in written)
        store.edit { it[DataStoreSettingsRepository.SETTINGS_KEY] = written }
        val repository = DataStoreSettingsRepository(store)

        assertEquals(customised, repository.get())
        assertEquals(customised.copy(memorySeedVersion = 1), repository.update { it.copy(memorySeedVersion = 1) })
        assertNull(store.data.first()[DataStoreSettingsRepository.UNREADABLE_COPY_KEY], "a missing field is not damage")
        assertTrue(store.data.first()[DataStoreSettingsRepository.SETTINGS_KEY]!!.endsWith(""","memorySeedVersion":1}"""))
    }

    @Test
    fun `fields added by a newer version are ignored and missing fields take their defaults`() = runTest {
        val (store, _) = stores.open(stores.newFileName())
        store.edit {
            it[DataStoreSettingsRepository.SETTINGS_KEY] =
                """{"localOnlyMode":true,"maxConcurrentTasks":6,"fromTheFuture":{"nested":[1,2]}}"""
        }
        assertEquals(
            FrameworkSettings(localOnlyMode = true, maxConcurrentTasks = 6),
            DataStoreSettingsRepository(store).get(),
        )
    }

    @Test
    fun `every field is written, so stored settings do not change when a default changes`() = runTest {
        val (store, _) = stores.open(stores.newFileName())
        DataStoreSettingsRepository(store).update { it }
        val stored = store.data.first()[DataStoreSettingsRepository.SETTINGS_KEY]!!
        assertTrue("\"taskTimeoutMinutes\":15" in stored, stored)
        assertTrue("\"directorAgentId\":null" in stored, stored)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an unreadable file is never replaced by defaults - reads fail and observers wait until it is readable`() = runTest {
        val stored = mutablePreferencesOf(DataStoreSettingsRepository.SETTINGS_KEY to """{"localOnlyMode":true}""")
        val unreliable = UnreliableStore(failures = 4, stored = stored)
        val repository = DataStoreSettingsRepository(unreliable)

        assertFailsWith<IOException> { repository.get() }
        assertFailsWith<IOException> { repository.update { it.copy(localOnlyMode = false) } }
        assertEquals(stored, unreliable.stored, "a failed update must not write anything")

        repository.settings.test {
            expectNoEvents() // the first read failed
            advanceTimeBy(1_000)
            runCurrent()
            expectNoEvents() // so did the retry one second later
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(FrameworkSettings(localOnlyMode = true), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(FrameworkSettings(localOnlyMode = true), repository.get())
    }

    @Test
    fun `errors other than I O failures are not hidden`() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("bug") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
        }
        assertFailsWith<IllegalStateException> { DataStoreSettingsRepository(broken).get() }
        assertFailsWith<IllegalStateException> { DataStoreSettingsRepository(broken).settings.first() }
    }

    /** A store whose first [failures] accesses fail with an I/O error, like a temporarily unreadable file. */
    private class UnreliableStore(private var failures: Int, stored: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(stored)
        val stored: Preferences get() = state.value

        private fun access() {
            if (failures > 0) {
                failures--
                throw IOException("disk error")
            }
        }

        override val data: Flow<Preferences> = flow {
            access()
            emit(state.value)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            access()
            return transform(state.value).also { state.value = it }
        }
    }
}
