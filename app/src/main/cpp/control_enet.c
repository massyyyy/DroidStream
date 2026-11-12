#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include <enet/enet.h>

#define TAG "DroidStreamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ENetHost *g_host = NULL;
static volatile int g_running = 0;

static void *service_thread(void *arg) {
    (void) arg;
    while (g_running) {
        ENetEvent event;
        int n = enet_host_service(g_host, &event, 100);
        if (n > 0) {
            switch (event.type) {
                case ENET_EVENT_TYPE_CONNECT:
                    LOGI("ENet CONNECT: canali=%u data=%u",
                         (unsigned) event.peer->channelCount, (unsigned) event.data);
                    break;
                case ENET_EVENT_TYPE_RECEIVE:
                    LOGI("ENet RECV: canale=%u len=%u",
                         (unsigned) event.channelID, (unsigned) event.packet->dataLength);
                    enet_packet_destroy(event.packet);   /* per ora scarta: M3c li interpretera' */
                    break;
                case ENET_EVENT_TYPE_DISCONNECT:
                    LOGI("ENet DISCONNECT");
                    break;
                default:
                    break;
            }
        }
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_org_droidstream_host_NativeBridge_enetStart(JNIEnv *env, jclass clazz, jint port) {
    (void) env; (void) clazz;
    if (g_running) return JNI_TRUE;
    if (enet_initialize() != 0) { LOGE("enet_initialize fallito"); return JNI_FALSE; }

    ENetAddress addr;
    addr.host = ENET_HOST_ANY;
    addr.port = (enet_uint16) port;

    g_host = enet_host_create(&addr,
                              1,   /* un solo peer: il client */
                              8,   /* canali: abbastanza per CTRL_CHANNEL_COUNT */
                              0, 0);
    if (!g_host) { LOGE("enet_host_create fallito"); return JNI_FALSE; }

    g_running = 1;
    pthread_t t;
    if (pthread_create(&t, NULL, service_thread, NULL) != 0) {
        LOGE("pthread_create fallito");
        g_running = 0;
        enet_host_destroy(g_host); g_host = NULL;
        return JNI_FALSE;
    }
    pthread_detach(t);
    LOGI("ENet control server in ascolto su UDP %d", port);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_droidstream_host_NativeBridge_enetStop(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    g_running = 0;
    if (g_host) { enet_host_destroy(g_host); g_host = NULL; }
    enet_deinitialize();
}
