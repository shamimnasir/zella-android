#!/bin/zsh
# Test media that cannot live in the repo. Builds a wide test video with a real face off to the left,
# from MediaPipe's public portrait test image, and pushes it to the app's files folder.
set -e
OUT=${TMPDIR:-/tmp}/zella-test-media
mkdir -p $OUT
curl -sSfL -o $OUT/portrait.jpg https://storage.googleapis.com/mediapipe-assets/portrait.jpg
ffmpeg -v error -y -loop 1 -i $OUT/portrait.jpg -f lavfi -i "color=c=0x334455:s=1920x1080:d=5" \
  -filter_complex "[0:v]scale=-2:1080[p];[1:v][p]overlay=120:0:shortest=1" -t 5 -r 30 -c:v libx264 -pix_fmt yuv420p $OUT/face-wide.mp4
adb push $OUT/face-wide.mp4 /sdcard/Android/data/com.zellahq.app/files/face-wide.mp4
echo pushed
