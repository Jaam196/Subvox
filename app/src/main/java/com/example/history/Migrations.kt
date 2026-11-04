package com.example.history

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Catálogo central de migraciones incrementales de Room para [SubtitleHistoryDatabase].
 *
 * Evita la pérdida destructiva de datos y permite evolucionar las tablas
 * de historial y glosario entre versiones del esquema.
 */
object Migrations {

    /**
     * Ejemplo documentado de migración incremental:
     * De versión 1 a versión 2 (ej. adición de columna opcional para tags o metadatos).
     *
     * Si en el futuro se incrementa `version = 2` en [SubtitleHistoryDatabase],
     * se descomenta y se añade en el builder `addMigrations(MIGRATION_1_2)`.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Ejemplo de evolución de esquema sin borrar registros existentes:
            // db.execSQL("ALTER TABLE subtitle_history_records ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Lista de todas las migraciones activas a registrar en el Room.databaseBuilder.
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        // Añadir aquí futuras migraciones: MIGRATION_1_2, MIGRATION_2_3, etc.
    )
}
