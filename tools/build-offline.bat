@echo off
setlocal
cd /d "%~dp0\.."
if exist build rmdir /s /q build
mkdir build\offline-stubs
mkdir build\classes
mkdir build\offline-resources
mkdir build\libs
(for /r tools\paper-compile-stubs %%f in (*.java) do echo "%%f") > build\stubs.txt
javac -encoding UTF-8 -d build\offline-stubs @build\stubs.txt
(for /r src\main\java %%f in (*.java) do echo "%%f") > build\sources.txt
javac --release 21 -encoding UTF-8 -cp build\offline-stubs -d build\classes @build\sources.txt
xcopy src\main\resources build\offline-resources /e /i /q
jar --create --file build\libs\LTZCore-0.5.15-Haven.jar -C build\classes . -C build\offline-resources .
mkdir build\shard-test
javac --release 21 -encoding UTF-8 -cp build\classes -d build\shard-test tools\tests\ShardCountdownSelfTest.java
java -cp "build\classes;build\shard-test" ShardCountdownSelfTest
mkdir build\money-test
javac --release 21 -encoding UTF-8 -cp build\classes -d build\money-test tools\tests\MoneyFormatSelfTest.java
java -cp "build\classes;build\money-test" MoneyFormatSelfTest
mkdir build\combat-test
javac --release 21 -encoding UTF-8 -cp build\classes -d build\combat-test tools\tests\CombatCountdownSelfTest.java
java -cp "build\classes;build\combat-test" CombatCountdownSelfTest
echo Built build\libs\LTZCore-0.5.15-Haven.jar
endlocal
