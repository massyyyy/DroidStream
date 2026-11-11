#include <jni.h>
#include <string>
#include <time.h>

static long long now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + (long long)ts.tv_nsec;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_droidstream_host_NativeBridge_info(JNIEnv* env, jobject /*this*/) {
    long long best = 1LL << 60;
    const int N = 1000;
    long long t0 = now_ns();
    for (int i = 0; i < N; i++) {
        long long a = now_ns();
        long long b = now_ns();
        long long d = b - a;
        if (d < best) best = d;
    }
    long long total_us = (now_ns() - t0) / 1000;

    std::string s = "native OK | C++ " + std::to_string((long)__cplusplus);
    s += " | " + std::to_string((long)(sizeof(void*) * 8)) + " bit";
#if defined(__ARM_NEON)
    s += " | NEON si";
#else
    s += " | NEON no";
#endif
    s += " | clock_gettime min " + std::to_string(best) + " ns";
    s += " | 1000 chiamate " + std::to_string(total_us) + " us";
    return env->NewStringUTF(s.c_str());
}