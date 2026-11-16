# Shared macOS environment for the Sokoul shell scripts. Sourced, never run directly.
# Checks the platform and the tools Gradle needs, and picks a JDK 17+ for Gradle when JAVA_HOME is not set.

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "These scripts are for macOS. On Windows use the .ps1 scripts and gradlew.bat." >&2
    exit 1
fi

# A Finder double-click (.command) starts a login shell, but make Homebrew's tools visible in every case.
for brew_bin in /opt/homebrew/bin /usr/local/bin; do
    [[ -d "$brew_bin" && ":$PATH:" != *":$brew_bin:"* ]] && PATH="$brew_bin:$PATH"
done
export PATH

if [[ -z "${JAVA_HOME:-}" ]]; then
    # The modules compile with a Java 17 toolchain: prefer exactly 17, otherwise any newer JDK runs Gradle.
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 17+ 2>/dev/null || true)"
    if [[ -z "$JAVA_HOME" ]]; then
        echo "No JDK 17+ found. Install one, for example: brew install openjdk@17" >&2
        exit 1
    fi
    export JAVA_HOME
fi

if ! command -v node >/dev/null 2>&1; then
    echo "Node.js 20+ is required (it generates the extractor bundle). Install it, for example: brew install node" >&2
    exit 1
fi

# gradlew may come from a Windows checkout without its executable bit.
[[ -x ./gradlew ]] || chmod +x ./gradlew
