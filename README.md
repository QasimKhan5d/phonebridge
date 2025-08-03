# phonebridge

Download the `.task` file Gemma-3n-E2B-it-int4 from [HuggingFace](https://huggingface.co/google/gemma-3n-E2B-it-litert-preview).

Next, run the following to push Gemma to the android app.

    adb shell rm -r /storage/emulated/0/Android/data/com.example.braillebridge2/files
    adb shell mkdir -p /storage/emulated/0/Android/data/com.example.braillebridge2/files
    adb push gemma-3n-E2B-it-int4.task /storage/emulated/0/Android/data/com.example.braillebridge2/files/gemma-3n-E2B-it-int4.task