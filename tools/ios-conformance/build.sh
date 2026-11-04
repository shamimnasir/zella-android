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
build duck && $W/duck/run $FX/duck-ramps.json
build motion-dump && $W/motion-dump/run $ROOT/zella-core/src/main/resources/motion-catalog.json $FX/motion-frames.json
echo "fixtures written to $FX"
# The other direction: the iPhone decoder reads what Android wrote (run ./gradlew :zella-core:test first).
build read-android && $W/read-android/run $ROOT/zella-core/build/ios-check

# Looks: the iPhone's EffectChain compiled in the same module as ZellaCore (the catalogue is internal).
mkdir -p $W/bake/src && cp $W/src/*.swift $W/bake/src/
R=$REPO/ZellaiOS/Packages/ZellaKit/Sources/ZellaRender
cp $R/EffectChain.swift $R/LUTLoader.swift $W/bake/src/
sed -i '' '/^import ZellaCore$/d' $W/bake/src/EffectChain.swift $W/bake/src/LUTLoader.swift
cp $HERE/bake-looks.swift $W/bake/src/main.swift
xcrun swiftc -module-name Bake -target arm64-apple-macos15 -swift-version 6 -I $W/mods -I $W/shim -Xcc -fmodule-map-file=$W/shim/module.modulemap -L $W -lZellaMotion -Xlinker -rpath -Xlinker $W $W/bake/src/*.swift $W/shim.o -o $W/bake/run
DYLD_LIBRARY_PATH=$W $W/bake/run $W/bake/out
cp $W/bake/out/looks/*.lut $W/bake/out/looks.json $ROOT/app/src/main/assets/looks/
cp $W/bake/out/board/* $ROOT/zella-core/src/test/resources/fixtures/ios/board/
echo "looks baked into app/src/main/assets/looks"

# Music loops and sound effects: the iPhone's own synthesiser (SoundFX.swift), compressed to AAC.
build bake-sounds && $W/bake-sounds/run $W/sounds
D=$ROOT/app/src/main/assets/sounds; mkdir -p $D/music $D/sfx
for f in $W/sounds/music/*.wav; do ffmpeg -v error -y -i $f -c:a aac -b:a 128k $D/music/$(basename ${f%.wav}).m4a; done
for f in $W/sounds/sfx/*.wav; do ffmpeg -v error -y -i $f -ac 1 -c:a aac -b:a 96k $D/sfx/$(basename ${f%.wav}).m4a; done
cp $W/sounds/sounds.json $D/sounds.json

# Title looks: read out of the app target, which this build cannot compile.
python3 $HERE/title-looks.py $REPO/ZellaiOS/App/Features/Editor/StudioSheet.swift $ROOT/app/src/main/assets/titles/looks.json

# One-tap plans: the iPhone's planning functions on a fixed battery of inputs.
mkdir -p $W/plans
python3 $HERE/extract-plans.py $REPO/ZellaiOS/App/Features/Editor/EditorViewModel.swift $W/plans/plans.swift
cp $HERE/plans-main.swift $W/plans/main.swift
(cd $W/plans && xcrun swiftc -target arm64-apple-macos15 -swift-version 5 -I $W/mods -I $W/shim -Xcc -fmodule-map-file=$W/shim/module.modulemap \
  -L $W -lZellaCore -lZellaMotion main.swift plans.swift -o run && DYLD_LIBRARY_PATH=$W ./run $FX/plans.json)

# Sensitive text: the iPhone's matcher and blur planner on fixed inputs. This file stands alone.
mkdir -p $W/sensitive && cp $HERE/sensitive.swift $W/sensitive/main.swift
cp $REPO/ZellaiOS/Packages/ZellaKit/Sources/ZellaCore/SensitiveText.swift $W/sensitive/
(cd $W/sensitive && xcrun swiftc -O main.swift SensitiveText.swift -o run && ./run > $ROOT/zella-core/src/test/resources/fixtures/ios/sensitive.json)

# Keyframes and beat punches: the iPhone's own maths on fixed inputs.
mkdir -p $W/depth && cp $HERE/depth.swift $W/depth/main.swift
cp $REPO/ZellaiOS/Packages/ZellaKit/Sources/ZellaCore/{Keyframe,BeatSync}.swift $W/depth/
(cd $W/depth && xcrun swiftc -swift-version 5 main.swift Keyframe.swift BeatSync.swift -o run && ./run > $ROOT/zella-core/src/test/resources/fixtures/ios/depth.json)
