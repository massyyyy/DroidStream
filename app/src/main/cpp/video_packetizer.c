#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define TAG "DroidStreamNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    uint8_t  cc_src_len;
    uint8_t  payload_type;
    uint16_t seq_num;
    uint32_t timestamp;
    uint32_t ssrc;
} __attribute__((packed)) RTP_HEADER;

typedef struct {
    uint8_t packet_type;
    uint8_t frame_index;
    uint8_t flags;
    uint8_t stream_id;
} __attribute__((packed)) NV_VIDEO_HEADER;

static uint16_t g_seq_num = 0;
static uint32_t g_ssrc = 0x12345678;
static int g_sock = -1;

static int ensure_socket(void) {
    if (g_sock < 0) {
        g_sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (g_sock < 0) LOGE("socket() failed");
    }
    return g_sock;
}

JNIEXPORT jint JNICALL
Java_org_droidstream_host_NativeBridge_sendVideoFrame(JNIEnv *env, jclass clazz,
        jbyteArray frame_data, jint frame_index, jboolean is_keyframe, jint rtp_timestamp,
        jstring remote_addr, jint remote_port) {
    (void) clazz;
    (void) is_keyframe;

    jsize frame_len = (*env)->GetArrayLength(env, frame_data);
    const char *addr_str = (*env)->GetStringUTFChars(env, remote_addr, NULL);
    
    int sock = ensure_socket();
    if (sock < 0) goto cleanup;

    jbyte *frame_bytes = (*env)->GetByteArrayElements(env, frame_data, NULL);
    if (frame_bytes == NULL || addr_str == NULL) goto cleanup;

    struct sockaddr_in remote;
    memset(&remote, 0, sizeof(remote));
    remote.sin_family = AF_INET;
    remote.sin_port = htons((uint16_t) remote_port);
    inet_pton(AF_INET, addr_str, &remote.sin_addr);

    const int packet_size = 1392;
    const int header_size = (int) (sizeof(RTP_HEADER) + sizeof(NV_VIDEO_HEADER));
    const int max_payload = packet_size - header_size;

    int offset = 0;
    int packet_count = 0;

    while (offset < frame_len) {
        int remaining = frame_len - offset;
        int payload_size = (remaining < max_payload) ? remaining : max_payload;
        int packet_len = header_size + payload_size;

        uint8_t *packet = (uint8_t *) malloc((size_t) packet_len);
        if (packet == NULL) break;

        RTP_HEADER *rtp = (RTP_HEADER *) packet;
        rtp->cc_src_len   = 0x80;
        rtp->payload_type = 96;
        rtp->seq_num      = htons(g_seq_num++);
        rtp->timestamp    = htonl((uint32_t) rtp_timestamp);
        rtp->ssrc         = htonl(g_ssrc);

        NV_VIDEO_HEADER *nv = (NV_VIDEO_HEADER *) (packet + sizeof(RTP_HEADER));
        nv->packet_type = 0;
        nv->frame_index = (uint8_t) (frame_index & 0xFF);
        nv->flags = 0;
        if (offset == 0) nv->flags |= 0x01; // SOF
        if (offset + payload_size >= frame_len) nv->flags |= 0x02; // EOF
        nv->stream_id = 0;

        memcpy(packet + header_size, frame_bytes + offset, (size_t) payload_size);
        
        int sent = sendto(sock, packet, (size_t) packet_len, 0,
               (const struct sockaddr *) &remote, sizeof(remote));
        if (sent >= 0) {
            packet_count++;
        }
        free(packet);

        offset += payload_size;
    }

cleanup:
    if (frame_bytes != NULL) {
        (*env)->ReleaseByteArrayElements(env, frame_data, frame_bytes, JNI_ABORT);
    }
    if (addr_str != NULL) {
        (*env)->ReleaseStringUTFChars(env, remote_addr, addr_str);
    }
    
    return packet_count;
}
