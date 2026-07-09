/*
 * Minimal QCOW2 v3 image creator — JNI-accessible from QemuImgBridge.kt.
 * Package: tech.ula.library.qemu
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>

#define TAG "QemuImg"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define CLUSTER_BITS    16
#define CLUSTER_SIZE    (1 << CLUSTER_BITS)
#define QCOW2_MAGIC     0x514649fbU
#define REFCOUNT_ORDER  4
#define REFCOUNT_ENTRY_SZ 2

static void wr16(uint8_t *p, uint16_t v) {
    p[0] = (v >> 8) & 0xff;  p[1] = v & 0xff;
}
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (v >> 24) & 0xff; p[1] = (v >> 16) & 0xff;
    p[2] = (v >>  8) & 0xff; p[3] =  v         & 0xff;
}
static void wr64(uint8_t *p, uint64_t v) {
    p[0] = (v >> 56) & 0xff; p[1] = (v >> 48) & 0xff;
    p[2] = (v >> 40) & 0xff; p[3] = (v >> 32) & 0xff;
    p[4] = (v >> 24) & 0xff; p[5] = (v >> 16) & 0xff;
    p[6] = (v >>  8) & 0xff; p[7] =  v         & 0xff;
}

static int write_all(int fd, const void *buf, size_t n) {
    const uint8_t *p = (const uint8_t *)buf;
    while (n > 0) {
        ssize_t r = write(fd, p, n);
        if (r <= 0) return -1;
        p += r; n -= (size_t)r;
    }
    return 0;
}

static int write_zeroes(int fd, size_t n) {
    uint8_t z[4096];
    memset(z, 0, sizeof(z));
    while (n > 0) {
        size_t chunk = n < sizeof(z) ? n : sizeof(z);
        if (write_all(fd, z, chunk) != 0) return -1;
        n -= chunk;
    }
    return 0;
}

static int qcow2_create(const char *path, uint64_t virtual_size)
{
    const uint64_t L1_OFFSET  = (uint64_t)CLUSTER_SIZE * 1;
    const uint64_t RCT_OFFSET = (uint64_t)CLUSTER_SIZE * 2;
    const uint64_t RCB_OFFSET = (uint64_t)CLUSTER_SIZE * 3;
    const uint64_t L2_ENTRIES = CLUSTER_SIZE / 8;
    const uint64_t L2_COVER   = L2_ENTRIES * CLUSTER_SIZE;
    uint32_t l1_size = (uint32_t)((virtual_size + L2_COVER - 1) / L2_COVER);
    if (l1_size == 0) l1_size = 1;

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { LOGE("open(%s): %s", path, strerror(errno)); return -1; }

    uint8_t c0[CLUSTER_SIZE];
    memset(c0, 0, sizeof(c0));
    wr32(c0 +   0, QCOW2_MAGIC);
    wr32(c0 +   4, 3);
    wr64(c0 +   8, 0);
    wr32(c0 +  16, 0);
    wr32(c0 +  20, CLUSTER_BITS);
    wr64(c0 +  24, virtual_size);
    wr32(c0 +  32, 0);
    wr32(c0 +  36, l1_size);
    wr64(c0 +  40, L1_OFFSET);
    wr64(c0 +  48, RCT_OFFSET);
    wr32(c0 +  56, 1);
    wr32(c0 +  60, 0);
    wr64(c0 +  64, 0);
    wr64(c0 +  72, 0);
    wr64(c0 +  80, 0);
    wr64(c0 +  88, 0);
    wr32(c0 +  96, REFCOUNT_ORDER);
    wr32(c0 + 100, 104);
    if (write_all(fd, c0, CLUSTER_SIZE) != 0) goto err;
    if (write_zeroes(fd, CLUSTER_SIZE) != 0) goto err;

    uint8_t c2[CLUSTER_SIZE];
    memset(c2, 0, sizeof(c2));
    wr64(c2, RCB_OFFSET);
    if (write_all(fd, c2, CLUSTER_SIZE) != 0) goto err;

    uint8_t c3[CLUSTER_SIZE];
    memset(c3, 0, sizeof(c3));
    wr16(c3 + 0 * REFCOUNT_ENTRY_SZ, 1);
    wr16(c3 + 1 * REFCOUNT_ENTRY_SZ, 1);
    wr16(c3 + 2 * REFCOUNT_ENTRY_SZ, 1);
    wr16(c3 + 3 * REFCOUNT_ENTRY_SZ, 1);
    if (write_all(fd, c3, CLUSTER_SIZE) != 0) goto err;

    close(fd);
    LOGI("created qcow2: %s  virtual=%llu B", path, (unsigned long long)virtual_size);
    return 0;
err:
    LOGE("I/O error writing %s: %s", path, strerror(errno));
    close(fd);
    unlink(path);
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_tech_ula_library_qemu_QemuImgBridge_create(
        JNIEnv *env, jobject thiz, jstring jPath, jlong sizeBytes)
{
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int ret = qcow2_create(path, (uint64_t)sizeBytes);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tech_ula_library_qemu_QemuImgBridge_delete(
        JNIEnv *env, jobject thiz, jstring jPath)
{
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int ret = unlink(path);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return (ret == 0 || errno == ENOENT) ? JNI_TRUE : JNI_FALSE;
}
