#include <nativehelper/JNIHelp.h>

namespace android {

int register_android_graphics_pdf_PdfRenderer(JNIEnv* env);

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    if (register_android_graphics_pdf_PdfRenderer(env) < 0) return JNI_ERR;

    return JNI_VERSION_1_6;
}

};  // namespace android