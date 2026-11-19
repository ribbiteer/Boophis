# Source from Git Bash: . tools/env.sh
# Finds the newest JDK 21 under ~/.jdks and the Android SDK in its default Windows location unless
# JAVA_HOME / ANDROID_HOME are already set.
if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME=$(ls -d "$HOME"/.jdks/jdk-21* 2>/dev/null | sort | tail -1)
  export JAVA_HOME
fi
: "${ANDROID_HOME:=$(cygpath -m "${LOCALAPPDATA:-$HOME/AppData/Local}")/Android/Sdk}"
export ANDROID_HOME
export PATH="$JAVA_HOME/bin:$(cygpath -u "$ANDROID_HOME")/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1
