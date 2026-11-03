#!/bin/zsh
# Builds the iPhone app's ZellaCore for macOS from a local checkout and runs the conformance
# programs. Nothing in the iPhone checkout is modified: sources are copied to a work folder.
# Usage: tools/ios-conformance/build.sh [path to the Zella repo, default ~/Desktop/Zella]
set -e
REPO=${1:-$HOME/Desktop/Zella}
HERE=${0:A:h}
ROOT=${HERE:h:h}
W=$(mktemp -d)
echo "work folder $W"
mkdir -p $W/src $W/motion $W/shim $W/mods $W/media $W/out
cp $REPO/ZellaiOS/Packages/ZellaKit/Sources/ZellaCore/*.swift $W/src/
cp $REPO/Shared/ZellaMotion/Sources/ZellaMotion/*.swift $W/motion/
S=$REPO/ZellaiOS/Packages/ZellaKit/Sources/ZellaObjCShim
cp $S/include/ZellaObjCShim.h $W/shim/
printf 'module ZellaObjCShim {\n  header "ZellaObjCShim.h"\n  export *\n}\n' > $W/shim/module.modulemap
# 2026-11-04: one LookLibrary line did not compile on its own (ColorPreset.subjectPop). It does not
# touch the project format, so it is commented out in the copy only.
grep -q "p.subjectPop = subjectPop" $W/src/LookLibrary.swift && sed -i '' 's|^\( *\)p.subjectPop = subjectPop|\1// &|' $W/src/LookLibrary.swift
cd $W
xcrun clang -c -fobjc-arc -target arm64-apple-macos15 -I shim $S/ZellaObjCShim.m -o shim.o
xcrun swiftc -module-name ZellaMotion -parse-as-library -emit-library -emit-module -emit-module-path mods/ZellaMotion.swiftmodule -o libZellaMotion.dylib -target arm64-apple-macos15 -swift-version 5 motion/*.swift
xcrun swiftc -module-name ZellaCore -parse-as-library -emit-library -emit-module -emit-module-path mods/ZellaCore.swiftmodule -o libZellaCore.dylib -I shim -I mods -L . -lZellaMotion -Xcc -fmodule-map-file=shim/module.modulemap -target arm64-apple-macos15 -swift-version 6 src/*.swift shim.o
build() { mkdir -p $W/$1; cp $HERE/$1.swift $W/$1/main.swift; xcrun swiftc -target arm64-apple-macos15 -swift-version 6 -I mods -I shim -Xcc -fmodule-map-file=shim/module.modulemap -L . -lZellaCore -lZellaMotion -Xlinker -rpath -Xlinker $W $W/$1/main.swift -o $W/$1/run; }
for n in a b c; do ffmpeg -v error -y -f lavfi -i "testsrc2=s=720x1280:r=30:d=6" -f lavfi -i "sine=f=440:d=6" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest media/clip_$n.mp4; done
ffmpeg -v error -y -f lavfi -i "sine=f=220:d=20" -c:a aac media/bed.m4a
FX=$ROOT/zella-core/src/test/resources/fixtures/ios
build fixtures && $W/fixtures/run $W
cp "$W/out/Conformance basic.zella/project.json" $FX/basic.project.json
cp "$W/out/Conformance empty.zella/project.json" $FX/empty.project.json
build probe && $W/probe/run | head -1 > $FX/absolute-ramp.project.json
build edits && $W/edits/run $FX/edit-steps.json
echo "fixtures written to $FX"
# The other direction: the iPhone decoder reads what Android wrote (run ./gradlew :zella-core:test first).
build read-android && $W/read-android/run $ROOT/zella-core/build/ios-check
