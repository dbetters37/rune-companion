package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.backup.Passphrases
import java.security.SecureRandom
import java.util.Random

/**
 * The save screen's passphrase choice, ready for a view model and free of Compose (rule 27; WP10 builds the screen).
 *
 * The generated passphrase is the default: shown once in groups ("7KQ2-M9XD-…", [generated]) with
 * [BackupTexts.WRITE_IT_DOWN]. "Use my own" ([useOwn]) takes the text of the screen's passphrase field, which is a
 * `SecretTextField(state = …)` (Material's `OutlinedSecureTextField`: every character hidden, password keyboard, no
 * suggestions or learning) whose `TextFieldState` the view model owns; this class never keeps that text. A save takes
 * the passphrase only through [forSave], which hands `OwnerBackup.save` exactly what `Passphrases.problemForSave` must
 * see: the generated passphrase as both the passphrase and `generatedShown`, or the owner's own text with
 * `generatedShown = null` (never the field's text as `generatedShown`: security review, NEW-1).
 */
class SavePassphrase(private val random: Random = SecureRandom()) {
    /** The passphrase generated for this screen, in groups; null once [forget] ran. */
    var generated: String? = Passphrases.generate(random)
        private set

    /** True when the owner chose "Use my own". */
    var useOwn: Boolean = false

    /** Another generated passphrase (the owner asked for a new one before saving). */
    fun regenerate() {
        generated = Passphrases.generate(random)
    }

    /**
     * Why the Save button stays off, in the owner's words, or null: for "Use my own" what `Passphrases.problemForSave`
     * says of [typed] (the field's current text); for the generated one, only that it was forgotten.
     */
    fun problem(typed: CharSequence): String? = when {
        useOwn -> Passphrases.problemForSave(typed, generatedShown = null)
        generated == null -> "Choose a passphrase."
        else -> null
    }

    /** What to pass to `OwnerBackup.save`: the passphrase and `generatedShown`; null while [problem] gives a sentence. */
    fun forSave(typed: CharSequence): Pair<CharSequence, String?>? {
        if (problem(typed) != null) return null
        return if (useOwn) typed to null else generated?.let { it to it }
    }

    /** Drops the generated passphrase when the screen closes (the view model also clears its text field). */
    fun forget() {
        generated = null
    }
}
