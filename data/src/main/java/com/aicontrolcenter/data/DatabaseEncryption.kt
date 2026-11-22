package com.aicontrolcenter.data

/** How the database file of a [DataLayer] is protected at rest. */
sealed interface DatabaseEncryption {
    /**
     * The whole database file is encrypted with SQLCipher (AES-256, every page authenticated).
     * Its key is 32 random bytes created on first use, sealed by a key in the AndroidKeyStore
     * and kept under `noBackupFilesDir`, so the file opens on this device only. This is what
     * the app uses.
     */
    data object Keystore : DatabaseEncryption

    /**
     * Plain SQLite. Only for tests on the JVM, where neither the SQLCipher native library nor
     * a device keystore exists. A database created this way is readable by anything that can
     * read the file, and cannot be opened with [Keystore] later.
     */
    data object None : DatabaseEncryption
}
