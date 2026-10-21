package android.app

/** Minimal android.app.Activity stub (descriptor-only requirement). */
open class Activity {
    /** Android lifecycle; headless operations are no-ops. */
    protected open fun onCreate(savedInstanceState: android.os.Bundle?) {}
}
