#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <enet/enet.h>

#define TAG "DroidStreamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ENetHost *g_host = NULL;
static volatile int g_running = 0;
static JavaVM *g_jvm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_input_method = NULL;

/* Costanti magic dal protocollo Moonlight Input.h */
#define KEY_DOWN_EVENT_MAGIC               0x00000003
#define KEY_UP_EVENT_MAGIC                 0x00000004
#define MOUSE_MOVE_ABS_MAGIC               0x00000005
#define MOUSE_MOVE_REL_MAGIC               0x00000006
#define MOUSE_MOVE_REL_MAGIC_GEN5          0x00000007
#define MOUSE_BUTTON_DOWN_EVENT_MAGIC_GEN5 0x00000008
#define MOUSE_BUTTON_UP_EVENT_MAGIC_GEN5   0x00000009
#define SCROLL_MAGIC                       0x00000009
#define SCROLL_MAGIC_GEN5                  0x0000000A
#define SS_TOUCH_MAGIC                     0x55000002

/* Tipi di eventi passati a InputManager.kt */
#define TYPE_MOUSE_ABS   1
#define TYPE_MOUSE_DOWN  2
#define TYPE_MOUSE_UP    3
#define TYPE_TOUCH_DOWN  4
#define TYPE_TOUCH_UP    5
#define TYPE_TOUCH_MOVE  6
#define TYPE_KEY_DOWN    7
#define TYPE_KEY_UP      8
#define TYPE_SCROLL      9

#pragma pack(push, 1)
typedef struct {
    uint32_t size;  /* Big Endian */
    uint32_t magic; /* Little Endian */
} NV_INPUT_HEADER;

typedef struct {
    NV_INPUT_HEADER header;
    int16_t x;       /* Big Endian */
    int16_t y;       /* Big Endian */
    int16_t unused;
    int16_t width;   /* Big Endian */
    int16_t height;  /* Big Endian */
} NV_ABS_MOUSE_MOVE_PACKET;

typedef struct {
    NV_INPUT_HEADER header;
    uint8_t button;
} NV_MOUSE_BUTTON_PACKET;

typedef struct {
    NV_INPUT_HEADER header;
    uint8_t eventType; /* 0=hover, 1=down, 2=up, 3=move */
    uint8_t zero[1];
    uint16_t rotation;
    uint32_t pointerId;
    float x;
    float y;
    float pressureOrDistance;
    float contactAreaMajor;
    float contactAreaMinor;
} SS_TOUCH_PACKET;

typedef struct {
    NV_INPUT_HEADER header;
    char flags;
    int16_t keyCode; /* Little Endian */
    char modifiers;
    int16_t zero2;
} NV_KEYBOARD_PACKET;

typedef struct {
    NV_INPUT_HEADER header;
    int16_t scrollAmt1; /* Big Endian */
    int16_t scrollAmt2; /* Big Endian */
    int16_t zero3;
} NV_SCROLL_PACKET;
#pragma pack(pop)

static void dispatch_input(JNIEnv *env, int type, int x, int y, int button, int keyCode) {
    if (env && g_bridge_class && g_input_method) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_input_method,
                                     (jint)type, (jint)x, (jint)y, (jint)button, (jint)keyCode);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
}

static void parse_and_dispatch(JNIEnv *env, const uint8_t *data, size_t length) {
    if (length < sizeof(NV_INPUT_HEADER)) return;

    const NV_INPUT_HEADER *hdr = (const NV_INPUT_HEADER *) data;
    uint32_t magic = hdr->magic;

    if (magic == MOUSE_MOVE_ABS_MAGIC && length >= sizeof(NV_ABS_MOUSE_MOVE_PACKET)) {
        const NV_ABS_MOUSE_MOVE_PACKET *p = (const NV_ABS_MOUSE_MOVE_PACKET *) data;
        int16_t rx = (int16_t) ntohs((uint16_t) p->x);
        int16_t ry = (int16_t) ntohs((uint16_t) p->y);
        int16_t w = (int16_t) ntohs((uint16_t) p->width);
        int16_t h = (int16_t) ntohs((uint16_t) p->height);
        if (w > 0 && h > 0) {
            int norm_x = (int) ((((long long) rx) * 10000LL) / (long long) w);
            int norm_y = (int) ((((long long) ry) * 10000LL) / (long long) h);
            dispatch_input(env, TYPE_MOUSE_ABS, norm_x, norm_y, 0, 0);
        }
    } else if (magic == MOUSE_BUTTON_DOWN_EVENT_MAGIC_GEN5 && length >= sizeof(NV_MOUSE_BUTTON_PACKET)) {
        const NV_MOUSE_BUTTON_PACKET *p = (const NV_MOUSE_BUTTON_PACKET *) data;
        dispatch_input(env, TYPE_MOUSE_DOWN, 0, 0, (int) p->button, 0);
    } else if (magic == MOUSE_BUTTON_UP_EVENT_MAGIC_GEN5 && length >= sizeof(NV_MOUSE_BUTTON_PACKET)) {
        const NV_MOUSE_BUTTON_PACKET *p = (const NV_MOUSE_BUTTON_PACKET *) data;
        dispatch_input(env, TYPE_MOUSE_UP, 0, 0, (int) p->button, 0);
    } else if (magic == SS_TOUCH_MAGIC && length >= sizeof(SS_TOUCH_PACKET)) {
        const SS_TOUCH_PACKET *p = (const SS_TOUCH_PACKET *) data;
        int norm_x = (int) (p->x * 10000.0f);
        int norm_y = (int) (p->y * 10000.0f);
        int type = TYPE_TOUCH_MOVE;
        if (p->eventType == 1) type = TYPE_TOUCH_DOWN;
        else if (p->eventType == 2) type = TYPE_TOUCH_UP;
        dispatch_input(env, type, norm_x, norm_y, (int) p->pointerId, 0);
    } else if (magic == KEY_DOWN_EVENT_MAGIC && length >= sizeof(NV_KEYBOARD_PACKET)) {
        const NV_KEYBOARD_PACKET *p = (const NV_KEYBOARD_PACKET *) data;
        dispatch_input(env, TYPE_KEY_DOWN, 0, 0, 0, (int) p->keyCode);
    } else if (magic == KEY_UP_EVENT_MAGIC && length >= sizeof(NV_KEYBOARD_PACKET)) {
        const NV_KEYBOARD_PACKET *p = (const NV_KEYBOARD_PACKET *) data;
        dispatch_input(env, TYPE_KEY_UP, 0, 0, 0, (int) p->keyCode);
    } else if ((magic == SCROLL_MAGIC || magic == SCROLL_MAGIC_GEN5) && length >= sizeof(NV_SCROLL_PACKET)) {
        const NV_SCROLL_PACKET *p = (const NV_SCROLL_PACKET *) data;
        int16_t s1 = (int16_t) ntohs((uint16_t) p->scrollAmt1);
        int16_t s2 = (int16_t) ntohs((uint16_t) p->scrollAmt2);
        dispatch_input(env, TYPE_SCROLL, (int) s1, (int) s2, 0, 0);
    }
}

static void *service_thread(void *arg) {
    (void) arg;
    JNIEnv *env = NULL;
    if (g_jvm != NULL) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            LOGE("Impossibile connettere il thread ENet alla JVM");
            env = NULL;
        }
    }

    while (g_running) {
        ENetEvent event;
        int n = enet_host_service(g_host, &event, 50);
        if (n > 0) {
            switch (event.type) {
                case ENET_EVENT_TYPE_CONNECT:
                    LOGI("ENet CONNECT: canali=%u data=%u",
                         (unsigned) event.peer->channelCount, (unsigned) event.data);
                    break;
                case ENET_EVENT_TYPE_RECEIVE:
                    if (event.packet != NULL && event.packet->data != NULL) {
                        parse_and_dispatch(env, event.packet->data, event.packet->dataLength);
                        enet_packet_destroy(event.packet);
                    }
                    break;
                case ENET_EVENT_TYPE_DISCONNECT:
                    LOGI("ENet DISCONNECT");
                    break;
                default:
                    break;
            }
        }
    }

    if (g_jvm != NULL && env != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_org_droidstream_host_NativeBridge_enetStart(JNIEnv *env, jclass clazz, jint port) {
    (void) clazz;
    if (g_running) return JNI_TRUE;

    (*env)->GetJavaVM(env, &g_jvm);

    if (g_bridge_class == NULL) {
        jclass local_cls = (*env)->FindClass(env, "org/droidstream/host/NativeBridge");
        if (local_cls) {
            g_bridge_class = (jclass) (*env)->NewGlobalRef(env, local_cls);
            g_input_method = (*env)->GetStaticMethodID(env, g_bridge_class, "onNativeInput", "(IIIII)V");
        } else {
            LOGE("Classe NativeBridge non trovata per callback input");
        }
    }

    if (enet_initialize() != 0) {
        LOGE("enet_initialize fallito");
        return JNI_FALSE;
    }

    ENetAddress addr;
    addr.host = ENET_HOST_ANY;
    addr.port = (enet_uint16) port;

    g_host = enet_host_create(&addr,
                              1,   /* un solo peer: client Moonlight */
                              8,   /* canali: CTRL_CHANNEL_COUNT */
                              0, 0);
    if (!g_host) {
        LOGE("enet_host_create fallito");
        return JNI_FALSE;
    }

    g_running = 1;
    pthread_t t;
    if (pthread_create(&t, NULL, service_thread, NULL) != 0) {
        LOGE("pthread_create fallito");
        g_running = 0;
        enet_host_destroy(g_host);
        g_host = NULL;
        return JNI_FALSE;
    }
    pthread_detach(t);
    LOGI("ENet control server attivo su UDP %d con dispatch input", port);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_droidstream_host_NativeBridge_enetStop(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    g_running = 0;
    if (g_host) {
        enet_host_destroy(g_host);
        g_host = NULL;
    }
    enet_deinitialize();
}
