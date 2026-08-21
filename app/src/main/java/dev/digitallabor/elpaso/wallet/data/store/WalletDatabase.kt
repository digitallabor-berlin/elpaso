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
    ],
    version = 1,
    exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao

    abstract fun credentialMetadata(): CredentialMetadataDao

    abstract fun transactions(): TransactionDao

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
