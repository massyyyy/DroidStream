#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define TAG "DroidStreamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Standard RTP header (12 bytes) */
typedef struct {
    uint8_t  header;       /* 0x80 (V=2, P=0, X=0, CC=0) */
    uint8_t  payload_type; /* 97 (matches RTSP SDP a=rtpmap:97 H264/90000) */
    uint16_t seq_num;      /* Big Endian sequence number */
    uint32_t timestamp;    /* Big Endian RTP timestamp */
    uint32_t ssrc;         /* Big Endian SSRC identifier */
} __attribute__((packed)) RTP_HEADER;

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

/* 8-byte frame header for first packet of frame (SOF) when appversion >= 7.1.350 */
typedef struct {
    uint8_t  headerType;                /* 0x01 */
    uint16_t frame_processing_latency;  /* 0 */
    uint8_t  frameType;                 /* 2 = IDR/Keyframe, 1 = P-Frame */
    uint16_t lastPayloadLen;            /* 0 */
    uint8_t  unknown[2];                /* 0, 0 */
} __attribute__((packed)) VIDEO_SHORT_FRAME_HEADER;

static uint16_t g_seq_num = 0;
static uint32_t g_stream_packet_seq = 0;
static uint32_t g_ssrc = 0x12345678;
static int g_sock = -1;

static int ensure_socket(void) {
    if (g_sock < 0) {
        g_sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (g_sock < 0) {
            LOGE("socket() failed: %s", strerror(errno));
        } else {
            int sndbuf = 256 * 1024;
            setsockopt(g_sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
        }
    }
    return g_sock;
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

    struct sockaddr_in remote;
    memset(&remote, 0, sizeof(remote));
    remote.sin_family = AF_INET;
    remote.sin_port = htons((uint16_t) remote_port);
    inet_pton(AF_INET, addr_str, &remote.sin_addr);

    const int target_packet_size = 1392;
    uint8_t packet_buf[1500];

    int offset = 0;
    int packet_count = 0;

    while (offset < frame_len) {
        int is_first = (offset == 0);
        int header_size = (int)(sizeof(RTP_HEADER) + sizeof(NV_VIDEO_PACKET));
        if (is_first) {
            header_size += (int)sizeof(VIDEO_SHORT_FRAME_HEADER);
        }

        int max_payload = target_packet_size - header_size;
        int remaining = frame_len - offset;
        int payload_size = (remaining < max_payload) ? remaining : max_payload;
        int is_last = (offset + payload_size >= frame_len);

        int packet_len = header_size + payload_size;

        /* 1. Header RTP */
        RTP_HEADER *rtp = (RTP_HEADER *) packet_buf;
        rtp->header       = 0x80;
        rtp->payload_type = 97; /* Matches RTSP SDP a=rtpmap:97 */
        rtp->seq_num      = htons(g_seq_num++);
        rtp->timestamp    = htonl((uint32_t) rtp_timestamp);
        rtp->ssrc         = htonl(g_ssrc);

        /* 2. Header NV Video Packet */
        NV_VIDEO_PACKET *nv = (NV_VIDEO_PACKET *) (packet_buf + sizeof(RTP_HEADER));
        nv->streamPacketIndex = (g_stream_packet_seq++) << 8;
        nv->frameIndex        = (uint32_t) frame_index;
        nv->flags             = FLAG_CONTAINS_PIC_DATA;
        if (is_first) nv->flags |= FLAG_SOF;
        if (is_last)  nv->flags |= FLAG_EOF;
        nv->extraFlags        = 0;
        nv->multiFecFlags     = 0x10;
        nv->multiFecBlocks    = 0x00;
        nv->fecInfo           = 0;

        /* 3. Short Frame Header (Presente solo sul primo pacchetto della frame: SOF) */
        uint8_t *payload_dst = packet_buf + sizeof(RTP_HEADER) + sizeof(NV_VIDEO_PACKET);
        if (is_first) {
            VIDEO_SHORT_FRAME_HEADER *sfh = (VIDEO_SHORT_FRAME_HEADER *) payload_dst;
            sfh->headerType = 0x01;
            sfh->frame_processing_latency = 0;
            sfh->frameType = is_keyframe ? 2 : 1; /* 2 = IDR, 1 = P-Frame */
            sfh->lastPayloadLen = 0;
            sfh->unknown[0] = 0;
            sfh->unknown[1] = 0;
            payload_dst += sizeof(VIDEO_SHORT_FRAME_HEADER);
        }

        /* 4. Dati del frame video */
        memcpy(payload_dst, frame_bytes + offset, (size_t) payload_size);

        ssize_t sent = sendto(sock, packet_buf, (size_t) packet_len, 0,
                              (const struct sockaddr *) &remote, sizeof(remote));
        if (sent >= 0) {
            packet_count++;
        } else {
            LOGE("sendto() error: %s", strerror(errno));
        }

        offset += payload_size;
    }

    (*env)->ReleaseByteArrayElements(env, frame_data, frame_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, remote_addr, addr_str);

    return packet_count;
}
