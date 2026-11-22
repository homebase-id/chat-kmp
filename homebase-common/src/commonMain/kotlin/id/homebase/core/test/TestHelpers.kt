package id.homebase.core.test

/**
 * For tests: makes [languageTag] (BCP 47, e.g. "da") the locale that resources and formatting resolve to from now on. JVM and
 * Android set the default `Locale`; iOS sets the `AppleLanguages` user default; web does nothing, as its tests don't run it.
 */
expect fun setTestLocale(languageTag: String)