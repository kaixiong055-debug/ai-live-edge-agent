#!/usr/bin/env sh
set -e
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MAVEN_VERSION=3.9.9
MAVEN_HOME="$DIR/.mvn/wrapper/apache-maven-$MAVEN_VERSION"
MAVEN_BIN="$MAVEN_HOME/bin/mvn"

if [ ! -x "$MAVEN_BIN" ]; then
  mkdir -p "$DIR/.mvn/wrapper"
  ZIP="$DIR/.mvn/wrapper/apache-maven-$MAVEN_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip" -o "$ZIP"
  else
    wget -O "$ZIP" "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip"
  fi
  unzip -q -o "$ZIP" -d "$DIR/.mvn/wrapper"
fi

exec "$MAVEN_BIN" "$@"
