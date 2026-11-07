cd "$(dirname "$0")" || exit 1
./gradlew :core:test --no-daemon --console=plain "$@" 2>&1 | tail -60
