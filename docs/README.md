# Know bugs

None

# Recording locations

Android has several storage locations:

  * /data/data/ - internal user storage on new android
  * /storage/self/primary/ - internal storage
  * /storage/emulated/0/ - external storage (sdcard formated as internal or emulated external)
  * /storage/1D13-0F08/ - sdcard formated as portable

Some Androids can have more:

/storage/sdcard - emulated external sdcard
/data/user/0/ - internal user storage
/sdcard - link for default extenral storage /storage/emulated/0/

Plus application / user specified folders (all combinations of):

  * .../Android/data/com.github.axet.audiorecorder/files
  * .../com.github.axet.audiorecorder/files
  * .../Audio Recorder

For example:

  * /data/data/com.github.axet.audiorecorder/files
  * /data/user/0/com.github.axet.audiorecorder/files
  * /sdcard/Android/data/com.github.axet.audiorecorder/files
  * /sdcard/Audio Recorder

# Adb commands

    # adb shell am start -n com.github.axet.audiorecorder/.activities.RecordingActivity

    # adb shell am broadcast -a com.github.axet.audiorecorder.STOP_RECORDING
