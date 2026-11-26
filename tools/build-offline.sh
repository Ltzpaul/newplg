#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build/offline-stubs build/classes build/offline-resources build/libs
mkdir -p build/offline-stubs build/classes build/offline-resources build/libs
javac -encoding UTF-8 -d build/offline-stubs $(find tools/paper-compile-stubs -name '*.java')
javac --release 21 -encoding UTF-8 -cp build/offline-stubs -d build/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/offline-resources/
jar --create --file build/libs/LTZCore-0.5.15-Haven.jar -C build/classes . -C build/offline-resources .
mkdir -p build/shard-test build/money-test
javac --release 21 -encoding UTF-8 -cp build/classes -d build/shard-test tools/tests/ShardCountdownSelfTest.java
java -cp build/classes:build/shard-test ShardCountdownSelfTest
javac --release 21 -encoding UTF-8 -cp build/classes -d build/money-test tools/tests/MoneyFormatSelfTest.java
java -cp build/classes:build/money-test MoneyFormatSelfTest
mkdir -p build/combat-test
javac --release 21 -encoding UTF-8 -cp build/classes -d build/combat-test tools/tests/CombatCountdownSelfTest.java
java -cp build/classes:build/combat-test CombatCountdownSelfTest
echo "Built build/libs/LTZCore-0.5.15-Haven.jar (compiled with minimal compile-time API stubs; use GitHub Actions/Gradle for the official Paper API build)."
