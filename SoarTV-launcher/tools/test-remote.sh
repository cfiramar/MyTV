#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/remote-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/remote-tests \
  app/src/io/soartv/SkyworthRemote.java tests/SkyworthRemoteTest.java \
  app/src/io/soartv/SabrinaRemote.java tests/SabrinaRemoteTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/remote-tests io.soartv.SkyworthRemoteTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/remote-tests io.soartv.SabrinaRemoteTest
rm -rf out/remote-tests
