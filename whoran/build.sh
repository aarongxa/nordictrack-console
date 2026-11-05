#!/bin/sh
set -e
cd "$(dirname "$0")"
TOP=$(cd .. && pwd)
export JAVA_HOME="$TOP/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
BT="$TOP/sdk/build-tools/34.0.0"; JAR="$TOP/sdk/platforms/android-28/android.jar"
rm -rf build && mkdir -p build/classes build/dex build/res
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res/res.zip --min-sdk-version 24 --target-sdk-version 28
javac --release 8 -nowarn -Xlint:-options -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
python3 -c "import zipfile;z=zipfile.ZipFile(\"build/unsigned.apk\",\"a\",zipfile.ZIP_DEFLATED);z.write(\"build/dex/classes.dex\",\"classes.dex\");z.close()"
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out whoran.apk build/aligned.apk
"$BT/apksigner" verify --print-certs whoran.apk | head -3
sha256sum whoran.apk
