package com.aicontrolcenter.app.ui.components

/** What [testLabel] adds after a control's visible text. */
const val TEST_LABEL_SUFFIX: String = " (test)"

/**
 * Marks a placeholder: a control, navigation entry or section title whose feature does not
 * fully work yet. The visible text becomes "<label> (test)".
 *
 * Rule: any control that does not fully work yet carries "(test)" until it does. Build every
 * such label with this function and nothing else, so `testLabel(` finds them all. When a
 * feature becomes real, remove the call and its entry in `PlaceholderLabelsTest` (app unit
 * tests) in the same change. For a whole section or a setting that has no effect, label both
 * its entry (the navigation item or setting row) and its main control or title.
 *
 * `PlaceholderLabelsTest` opens each placeholder in the real app and fails if one loses its
 * label. It cannot tell on its own whether the feature behind it has become real (that would
 * mean testing the absence of code, such as nobody recording approval events), so removing a
 * label is a deliberate edit of that test's list.
 */
fun testLabel(label: String): String = label + TEST_LABEL_SUFFIX
