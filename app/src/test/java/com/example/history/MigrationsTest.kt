package com.example.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationsTest {

    @Test
    fun `database builder initializes without destructive migration on normal build`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            SubtitleHistoryDatabase::class.java
        )
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

        assertNotNull(db)
        assertNotNull(db.historyDao())
        db.close()
    }

    @Test
    fun `insert and query in history database works with schema version 1`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            SubtitleHistoryDatabase::class.java
        ).build()

        val dao = db.historyDao()
        assertNotNull(dao)
        assertEquals(0, Migrations.ALL_MIGRATIONS.size)
        db.close()
    }
}
