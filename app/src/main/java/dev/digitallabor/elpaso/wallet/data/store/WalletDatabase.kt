package dev.digitallabor.elpaso.wallet.data.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.digitallabor.elpaso.wallet.data.crypto.DbKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CredentialEntity::class,
        CredentialMetadataEntity::class,
        TransactionEntity::class,
        IssuerKeyEntity::class,
    ],
    // Bumped to 2 when `issuer_keys` and the two `credentials` binding columns were
    // added. This bump is NOT optional and `fallbackToDestructiveMigration()` does not
    // excuse it: Room hashes the schema and compares it against `room_master_table` on
    // open, and an unchanged version with a changed hash throws
    // "Room cannot verify the data integrity" *before* any migration path is consulted.
    // Destructive fallback only engages once the version number itself differs.
    // Any future entity or column change must bump this too.
    version = 2,
    exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao

    abstract fun credentialMetadata(): CredentialMetadataDao

    abstract fun transactions(): TransactionDao

    abstract fun issuerKeys(): IssuerKeyDao

    companion object {
        private const val DB_NAME = "elpaso.db"

        fun create(
            context: Context,
            dbKeyProvider: DbKeyProvider,
        ): WalletDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = dbKeyProvider.getOrCreatePassphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room
                .databaseBuilder(context, WalletDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
