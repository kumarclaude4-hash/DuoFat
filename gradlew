#!/usr/bin/env sh

##############################################################################
# Gradle wrapper script for POSIX systems
##############################################################################

# Resolve script location
APP_HOME=$(cd "$(dirname "$0")" && pwd)
APP_NAME="Gradle"

# Default JVM options
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

# Classpath
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Find Java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
        exit 1
    fi
else
    JAVACMD="java"
fi

# Increase max file descriptors if possible
MAX_FD_LIMIT=$(ulimit -H -n 2>/dev/null)
if [ "$MAX_FD_LIMIT" != "unlimited" ] && [ -n "$MAX_FD_LIMIT" ]; then
    ulimit -n "$MAX_FD_LIMIT" 2>/dev/null || true
fi

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    "-Dfile.encoding=UTF-8" \
    "-Dstdout.encoding=UTF-8" \
    "-Dstderr.encoding=UTF-8" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
