# Why each permission is asked for

Read from `app/src/main/AndroidManifest.xml`. Each is asked at the moment of use.

| Permission | Why | Asked |
| --- | --- | --- |
| INTERNET, ACCESS_NETWORK_STATE | The three optional paths. Video is never sent | Not a prompt |
| RECORD_AUDIO | Your voice. Android also needs it to capture other apps' sound | When recording starts |
| CAMERA | Camera recording. Not required: phones with no camera can still record the screen | When the camera screen opens |
| POST_NOTIFICATIONS | The "Zella is recording" notice with Pause and Stop, and export progress | When recording starts |
| FOREGROUND_SERVICE and the media projection, microphone and camera types | Recording keeps going when you leave the app | Not a prompt |
| FOREGROUND_SERVICE_MEDIA_PROCESSING, DATA_SYNC | Export keeps going when you leave the app. mediaProcessing on Android 15, dataSync before | Not a prompt |
| WAKE_LOCK | A long export keeps going with the screen off. Released when it ends, and never held past 6 hours | Not a prompt |
| BILLING (added by the library) | Buying Pro or Founder | Not a prompt |

Play asks for a declaration and a short video for the media projection foreground service. That video has to come
from a real phone, so it is on the owner's list in `RELEASE_CHECKLIST.md`.
