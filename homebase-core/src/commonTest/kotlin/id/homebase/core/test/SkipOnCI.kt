package id.homebase.core.test

/**
 * Marks a test that should be skipped in CI environments (GitHub Actions).
 * Typically used for tests that have environment-specific lifecycle issues
 * or require interactive/UI environments.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipOnCI