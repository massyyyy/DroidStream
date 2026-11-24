#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <pthread.h>
#include <poll.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define TAG "DroidStreamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Standard RTP header (12 bytes) */
typedef struct {
    uint8_t  header;       /* 0x90 (V=2, P=0, X=1, CC=0) - Extension bit required by Moonlight */
    uint8_t  payload_type; /* 97 (matches RTSP SDP a=rtpmap:97 H264/90000) */
    uint16_t seq_num;      /* Big Endian sequence number */
    uint32_t timestamp;    /* Big Endian RTP timestamp */
    uint32_t ssrc;         /* Big Endian SSRC identifier */
} __attribute__((packed)) RTP_HEADER;

/* 4 bytes extension padding required when X=1 */
typedef struct {
    uint16_t profile;      /* 0 */
    uint16_t length;       /* 0 */
} __attribute__((packed)) RTP_EXTENSION;

/* NV_VIDEO_PACKET header (16 bytes) - verified from moonlight-common-c Video.h */
#define FLAG_CONTAINS_PIC_DATA 0x01
#define FLAG_EOF               0x02
#define FLAG_SOF               0x04

typedef struct {
    uint32_t streamPacketIndex; /* (packetIndex << 8) in little-endian */
    uint32_t frameIndex;        /* frame index in little-endian */
    uint8_t  flags;             /* FLAG_CONTAINS_PIC_DATA | FLAG_SOF | FLAG_EOF */
    uint8_t  extraFlags;        /* 0 */
    uint8_t  multiFecFlags;     /* 0x10 */
    uint8_t  multiFecBlocks;    /* 0x00 */
    uint32_t fecInfo;           /* 0 */
} __attribute__((packed)) NV_VIDEO_PACKET;

/* Short video frame header (8 bytes) prepended to the payload of the first packet of each frame */
typedef struct {
    uint8_t  headerType;        /* 0x01 = short header */
    uint16_t frame_latency;     /* 0 */
    uint8_t  frameType;         /* 2 = IDR, 1 = P-frame */
    uint16_t lastPayloadLen;    /* payload length */
    uint8_t  unknown[2];        /* 0, 0 */
} __attribute__((packed)) SHORT_FRAME_HEADER;

static uint16_t g_seq_num = 0;
static uint32_t g_stream_packet_seq = 0;
static uint32_t g_ssrc = 0x12345678;
static int g_sock = -1;

static pthread_t g_ping_thread = 0;
static volatile int g_ping_thread_running = 0;
static volatile uint16_t g_discovered_client_port = 0;
static char g_discovered_client_ip[64] = {0};

static void *ping_listener_worker(void *arg) {
    (void) arg;
    LOGI("Ping listener thread started on UDP port 47998");
    while (g_ping_thread_running && g_sock >= 0) {
        struct pollfd pfd;
        pfd.fd = g_sock;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int ret = poll(&pfd, 1, 500);
        if (ret > 0 && (pfd.revents & POLLIN)) {
            struct sockaddr_in from;
            socklen_t from_len = sizeof(from);
            char buf[256];
            ssize_t n = recvfrom(g_sock, buf, sizeof(buf), 0,
                                 (struct sockaddr *)&from, &from_len);
            if (n > 0) {
                uint16_t port = ntohs(from.sin_port);
                char ip[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
                g_discovered_client_port = port;
                strncpy(g_discovered_client_ip, ip, sizeof(g_discovered_client_ip) - 1);
                LOGI("Discovered Moonlight video client: %s:%u (payload size=%zd)", ip, port, n);
            }
        }
    }
    LOGI("Ping listener thread exited");
    return NULL;
}

static void stop_ping_listener(void) {
    if (g_ping_thread_running) {
        g_ping_thread_running = 0;
        if (g_sock >= 0) {
            shutdown(g_sock, SHUT_RDWR);
        }
        if (g_ping_thread != 0) {
            pthread_join(g_ping_thread, NULL);
            g_ping_thread = 0;
        }
    }
}

static int ensure_socket(void) {
    if (g_sock < 0) {
        g_sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (g_sock < 0) {
            LOGE("socket() failed: %s", strerror(errno));
            return -1;
        }

        int opt = 1;
        setsockopt(g_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
#ifdef SO_REUSEPORT
        setsockopt(g_sock, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));
#endif

        struct sockaddr_in local_addr;
        memset(&local_addr, 0, sizeof(local_addr));
        local_addr.sin_family = AF_INET;
        local_addr.sin_port = htons(47998);
        local_addr.sin_addr.s_addr = htonl(INADDR_ANY);

        if (bind(g_sock, (struct sockaddr *) &local_addr, sizeof(local_addr)) < 0) {
            LOGE("bind(47998) failed: %s", strerror(errno));
        } else {
            LOGI("Video socket bound to local port 47998");
        }

        int sndbuf = 1024 * 1024;
        setsockopt(g_sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
        int rcvbuf = 256 * 1024;
        setsockopt(g_sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

        g_ping_thread_running = 1;
        if (pthread_create(&g_ping_thread, NULL, ping_listener_worker, NULL) != 0) {
            LOGE("pthread_create failed: %s", strerror(errno));
            g_ping_thread_running = 0;
        }
    }
    return g_sock;
}

JNIEXPORT jboolean JNICALL
Java_org_droidstream_host_NativeBridge_initVideoPacketizer(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    int sock = ensure_socket();
    return (sock >= 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_droidstream_host_NativeBridge_getDiscoveredClientPort(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) g_discovered_client_port;
}

JNIEXPORT void JNICALL
Java_org_droidstream_host_NativeBridge_resetVideoPacketizer(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    stop_ping_listener();
    g_seq_num = 0;
    g_stream_packet_seq = 0;
    g_discovered_client_port = 0;
    memset(g_discovered_client_ip, 0, sizeof(g_discovered_client_ip));
    if (g_sock >= 0) {
        close(g_sock);
        g_sock = -1;
    }
}

JNIEXPORT jint JNICALL
Java_org_droidstream_host_NativeBridge_sendVideoFrame(JNIEnv *env, jclass clazz,
        jbyteArray frame_data, jint frame_index, jboolean is_keyframe, jint rtp_timestamp,
        jstring remote_addr, jint remote_port) {
    (void) clazz;

    jsize frame_len = (*env)->GetArrayLength(env, frame_data);
    if (frame_len <= 0) return 0;

    const char *addr_str = (*env)->GetStringUTFChars(env, remote_addr, NULL);
    if (addr_str == NULL) return 0;

    int sock = ensure_socket();
    if (sock < 0) {
        (*env)->ReleaseStringUTFChars(env, remote_addr, addr_str);
        return 0;
    }

    jbyte *frame_bytes = (*env)->GetByteArrayElements(env, frame_data, NULL);
    if (frame_bytes == NULL) {
        (*env)->ReleaseStringUTFChars(env, remote_addr, addr_str);
        return 0;
    }

    uint16_t eff_port = g_discovered_client_port ? g_discovered_client_port : (uint16_t) remote_port;
    const char *eff_addr = (g_discovered_client_ip[0] != '\0') ? g_discovered_client_ip : addr_str;

    struct sockaddr_in remote;
    memset(&remote, 0, sizeof(remote));
    remote.sin_family = AF_INET;
    remote.sin_port = htons(eff_port);
    inet_pton(AF_INET, eff_addr, &remote.sin_addr);

    const int target_packet_size = 1392;
    uint8_t packet_buf[1500];

    int offset = 0;
    int packet_count = 0;

    const int base_headers_size = (int)(sizeof(RTP_HEADER) + sizeof(RTP_EXTENSION) + sizeof(NV_VIDEO_PACKET));

    while (offset < frame_len) {
        int is_first = (offset == 0);
        int cur_headers_size = base_headers_size + (is_first ? (int)sizeof(SHORT_FRAME_HEADER) : 0);
        int max_payload = target_packet_size - cur_headers_size;

        int remaining = frame_len - offset;
        int payload_size = (remaining < max_payload) ? remaining : max_payload;
        int is_last = (offset + payload_size >= frame_len);

        int packet_len = cur_headers_size + payload_size;

        /* 1. Header RTP */
        RTP_HEADER *rtp = (RTP_HEADER *) packet_buf;
        rtp->header       = 0x90; /* V=2, P=0, X=1 (extension present), CC=0 */
        rtp->payload_type = 97;   /* Matches RTSP SDP a=rtpmap:97 H264/90000 */
        rtp->seq_num      = htons(g_seq_num++);
        rtp->timestamp    = htonl((uint32_t) rtp_timestamp);
        rtp->ssrc         = htonl(g_ssrc);

        /* 2. Extension RTP (4 bytes) */
        RTP_EXTENSION *ext = (RTP_EXTENSION *) (packet_buf + sizeof(RTP_HEADER));
        ext->profile      = 0;
        ext->length       = 0;

        /* 3. Header NV Video Packet */
        NV_VIDEO_PACKET *nv = (NV_VIDEO_PACKET *) (packet_buf + sizeof(RTP_HEADER) + sizeof(RTP_EXTENSION));
        nv->streamPacketIndex = (g_stream_packet_seq++) << 8;
        nv->frameIndex        = (uint32_t) frame_index;
        nv->flags             = FLAG_CONTAINS_PIC_DATA;
        if (is_first) nv->flags |= FLAG_SOF;
        if (is_last)  nv->flags |= FLAG_EOF;
        nv->extraFlags        = 0;
        nv->multiFecFlags     = 0x10;
        nv->multiFecBlocks    = 0x00;
        nv->fecInfo           = 0;

        /* 4. Short Frame Header (8 bytes) only on the first packet */
        if (is_first) {
            SHORT_FRAME_HEADER *sfh = (SHORT_FRAME_HEADER *) (packet_buf + base_headers_size);
            sfh->headerType     = 0x01;
            sfh->frame_latency  = 0;
            sfh->frameType      = is_keyframe ? 2 : 1;
            sfh->lastPayloadLen = (uint16_t) payload_size;
            sfh->unknown[0]     = 0;
            sfh->unknown[1]     = 0;
        }

        /* 5. Dati del frame video H.264 (Annex-B NAL stream) */
        uint8_t *payload_dst = packet_buf + cur_headers_size;
        memcpy(payload_dst, frame_bytes + offset, (size_t) payload_size);

        ssize_t sent = sendto(sock, packet_buf, (size_t) packet_len, 0,
                              (const struct sockaddr *) &remote, sizeof(remote));
        if (sent >= 0) {
            packet_count++;
        } else {
            LOGE("sendto() error to %s:%u: %s", eff_addr, eff_port, strerror(errno));
        }

        offset += payload_size;
    }

    (*env)->ReleaseByteArrayElements(env, frame_data, frame_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, remote_addr, addr_str);

    return packet_count;
}
