package com.aicontrolcenter.data.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicontrolcenter.data.db.AiccDatabase
import org.junit.rules.ExternalResource

/** Gives each test its own empty in-memory database and closes it afterwards. */
class InMemoryDatabaseRule : ExternalResource() {
    private val lazyDatabase = lazy {
        AiccDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>())
    }

    val database: AiccDatabase by lazyDatabase

    override fun after() {
        if (lazyDatabase.isInitialized()) database.close()
    }
}
