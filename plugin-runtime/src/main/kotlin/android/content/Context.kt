package android.content

/**
 * Minimal android.content.Context stub for the headless JVM runtime.
 *
 * Community .cs3 plugins (e.g. SaurabhKaperwan/CSX) extend the app-module
 * Plugin class whose load(context: Context) mutates Android UI state. On the
 * desktop we cannot provide Android UI, but the LOAD path of these plugins
 * only uses the context to reach the settings key-value store
 * (CloudStreamApp.context -> DataStore.getSharedPrefs(context)), which our
 * stub chain answers. Verified against CineStream v487 bytecode
 * (javap of its dex2jar-converged jar): only type references to Context
 * resolve on the load path; context.* member calls happen exclusively in
 * settings-dialog / WebView code paths that never execute headless.
 */
open class Context
