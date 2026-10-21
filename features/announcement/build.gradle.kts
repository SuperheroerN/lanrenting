plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.strings)
  implementation(projects.core.data.api)
  implementation(projects.core.logging.api)

  implementation(libs.okhttp)
  implementation(libs.datastore)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
}
