#!/bin/zsh
# Builds Frost Fold straight from the Android SDK (no Gradle) into build/FrostFold.apk.
set -e
cd "${0:A:h}"
SDK=~/Library/Android/sdk
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar

rm -rf build && mkdir -p build/res build/gen build/classes
$BT/aapt2 compile --dir res -o build/res
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml --java build/gen \
    -o build/base.apk build/res/*.flat
javac --release 17 -classpath $JAR -d build/classes \
    $(find src build/gen -name '*.java')
$BT/d8 --min-api 31 --lib $JAR --output build $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --out build/FrostFold.apk build/aligned.apk
echo "Built build/FrostFold.apk"
