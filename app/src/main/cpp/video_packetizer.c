#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <string.h>
#include <stdint.h>

#define TAG "DroidStreamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// RTP header (12 byte)
typedef struct {
    uint8_t cc_src_len;    // 0x80 = version 2, no padding, no extension, 0 CSRC
    uint8_t payload_type;  // 96 = dynamic
    uint16_t seq_num;
    uint32_t timestamp;
    uint32_t ssrc;
} __attribute__((packed)) RTP_HEADER;

// NV Video header (4 byte dopo RTP)
typedef struct {
    uint8_t packet_type;   // 0 = data, 1 = FEC
    uint8_t frame_index;   // indice frame (mod 256)
    uint8_t flags;         // bit 0: SOF, bit 1: EOF
    uint8_t stream_id;     // 0 = video
} __attribute__((packed)) NV_VIDEO_HEADER;

static uint16_t g_seq_num = 0;
static uint32_t g_ssrc = 0x12345678;

JNIEXPORT jint JNICALL
Java_org_droidstream_host_NativeBridge_sendVideoFrame(JNIEnv *env, jclass clazz,
    jbyteArray frame_data, jint frame_index, jboolean is_keyframe,
    jstring remote_addr, jint remote_port) {
    
    jsize frame_len = (*env)->GetArrayLength(env, frame_data);
    jbyte *frame_bytes = (*env)->GetByteArrayElements(env, frame_data, NULL);
    
    const char *addr_str = (*env)->GetStringUTFChars(env, remote_addr, NULL);
    
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        LOGE("socket() failed");
        goto cleanup;
    }
    
    struct sockaddr_in remote;
    memset(&remote, 0, sizeof(remote));
    remote.sin_family = AF_INET;
    remote.sin_port = htons(remote_port);
    inet_pton(AF_INET, addr_str, &remote.sin_addr);
    
    // Parametri dal ANNOUNCE (dovrebbero essere passati da Kotlin)
    const int packet_size = 1392;  // max packet size
    const int rtp_header_size = sizeof(RTP_HEADER);
    const int nv_header_size = sizeof(NV_VIDEO_HEADER);
    const int max_payload = packet_size - rtp_header_size - nv_header_size;
    
    int offset = 0;
    int packet_count = 0;
    
    while (offset < frame_len) {
        int remaining = frame_len - offset;
        int payload_size = (remaining < max_payload) ? remaining : max_payload;
        
        // Alloca buffer per pacchetto
        int packet_len = rtp_header_size + nv_header_size + payload_size;
        uint8_t *packet = (uint8_t*)malloc(packet_len);
        
        // RTP header
        RTP_HEADER *rtp = (RTP_HEADER*)packet;
        rtp->cc_src_len = 0x80;  // version 2
        rtp->payload_type = 96;  // dynamic
        rtp->seq_num = htons(g_seq_num++);
        rtp->timestamp = htonl(frame_index * 1000);  // timestamp approssimato
        rtp->ssrc = htonl(g_ssrc);
        
        // NV Video header
        NV_VIDEO_HEADER *nv = (NV_VIDEO_HEADER*)(packet + rtp_header_size);
        nv->packet_type = 0;  // data
        nv->frame_index = (uint8_t)(frame_index & 0xFF);
        nv->flags = 0;
        if (offset == 0) nv->flags |= 0x01;  // SOF
        if (offset + payload_size >= frame_len) nv->flags |= 0x02;  // EOF
        nv->stream_id = 0;  // video
        
        // Payload
        memcpy(packet + rtp_header_size + nv_header_size, frame_bytes + offset, payload_size);
        
        // Invia
        sendto(sock, packet, packet_len, 0, (struct sockaddr*)&remote, sizeof(remote));
        
        free(packet);
        offset += payload_size;
        packet_count++;
    }
    
    close(sock);
    
cleanup:
    (*env)->ReleaseByteArrayElements(env, frame_data, frame_bytes, 0);
    (*env)->ReleaseStringUTFChars(env, remote_addr, addr_str);
    
    return packet_count;
}
