#!/usr/bin/env bash
# Sourced by Android build helpers. Match gradle/gradle-daemon-jvm.properties.
riftward_use_jdk17() {
  local riftward_candidate riftward_version riftward_macos_jdk=""
  if [[ -x /usr/libexec/java_home ]]; then
    riftward_macos_jdk="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  for riftward_candidate in "${JAVA_HOME:-}" \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    "$riftward_macos_jdk"; do
    [[ -n "$riftward_candidate" && -x "$riftward_candidate/bin/java" ]] || continue
    riftward_version="$("$riftward_candidate/bin/java" -version 2>&1)"
    if [[ "$riftward_version" == *'version "17.'* ]]; then
      export JAVA_HOME="$riftward_candidate"
      return 0
    fi
  done
  echo 'Riftward requires JDK 17. Install it and set JAVA_HOME to its home directory.' >&2
  return 1
}
riftward_use_jdk17
