#!/usr/bin/env sh
# Gradle Wrapper entrypoint for VisionVPN (Gradle 8.13)
# If gradle/wrapper/gradle-wrapper.jar is absent (e.g. fresh offline checkout),
# run `gradle wrapper --gradle-version 8.13` once or use GitHub Actions CI.
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  if command -v gradle >/dev/null 2>&1; then
    echo "Bootstrapping gradle-wrapper.jar (8.13)..."
    gradle wrapper --gradle-version 8.13
  else
    echo "ERROR: gradle/wrapper/gradle-wrapper.jar is missing and 'gradle' is not in PATH." >&2
    exit 1
  fi
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" ${DEFAULT_JVM_OPTS:-"-Xmx64m -Xms64m"} \
  "-Dorg.gradle.appname=gradlew" \
  -classpath "$WRAPPER_JAR" \
  org.gradle.wrapper.GradleWrapperMain "$@"
