package com.aicontrolcenter.app

/**
 * Marks a Phase 6 stub: a file or declaration that compiles and does nothing yet, created in wave 1 so that the
 * package named in [owner] fills it in its own files, without editing a file another package owns.
 *
 * The suite does not check it (rule 18, F25): a gate task at the end of wave 3 greps for this annotation and fails
 * while any stub is left. The package that fills a stub removes the annotation in the same change.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Phase6Stub(
    /** The package that fills it, such as "WP2". */
    val owner: String,
)
