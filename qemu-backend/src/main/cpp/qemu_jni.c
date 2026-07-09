#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include <fcntl.h>
#include <android/log.h>

#define TAG     "QemuJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern int qemu_android_main(int argc, char **argv, char **envp);

typedef struct {
    int    argc;
    char **argv;
} QemuArgs;

static volatile bool   s_running = false;
static volatile bool   s_stopping = false;
static pthread_t       s_thread;
static pthread_mutex_t s_mutex        = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  s_stopped_cond = PTHREAD_COND_INITIALIZER;
static char            s_socket_path[256] = "";

static int s_stderr_pipe[2] = {-1, -1};

static void *stderr_logger_thread(void *arg) {
    (void)arg;
    char buf[512];
    ssize_t n;
    while ((n = read(s_stderr_pipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        if (n > 0 && buf[n - 1] == '\n') buf[n - 1] = '\0';
        LOGE("QEMU: %s", buf);
    }
    return NULL;
}

static void redirect_stderr_to_logcat(void) {
    if (pipe(s_stderr_pipe) != 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        return;
    }
    dup2(s_stderr_pipe[1], STDOUT_FILENO);
    dup2(s_stderr_pipe[1], STDERR_FILENO);
    close(s_stderr_pipe[1]);
    s_stderr_pipe[1] = -1;
    setvbuf(stderr, NULL, _IONBF, 0);
    pthread_t t;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&t, &attr, stderr_logger_thread, NULL);
    pthread_attr_destroy(&attr);
    LOGI("stdout/stderr redirected to logcat");
}

static void free_args(QemuArgs *a) {
    for (int i = 0; i < a->argc; i++) free(a->argv[i]);
    free(a->argv);
    free(a);
}

static void *qemu_thread(void *arg) {
    QemuArgs *a = (QemuArgs *)arg;
    LOGI("QEMU thread starting (%d args)", a->argc);
    for (int i = 0; i < a->argc; i++)
        LOGI("  argv[%d] = %s", i, a->argv[i]);
    qemu_android_main(a->argc, a->argv, NULL);
    LOGI("QEMU thread exited");
    free_args(a);
    pthread_mutex_lock(&s_mutex);
    s_running = false;
    pthread_cond_broadcast(&s_stopped_cond);
    pthread_mutex_unlock(&s_mutex);
    return NULL;
}

/*
 * tech.ula.library.qemu.QemuBridge.start(...)
 *
 * Builds QEMU argv and launches on a pthread.
 * SLIRP networking with hostfwd for SSH (2022→2022) and VNC (5901→5901).
 */
JNIEXPORT jboolean JNICALL
Java_tech_ula_library_qemu_QemuBridge_start(
        JNIEnv       *env,
        jobject       thiz,
        jstring       jKernelPath,
        jstring       jInitrdPath,
        jstring       jSocketPath,
        jint          ramMb,
        jstring       jKernelCmdline,
        jobjectArray  jVirtfsMounts,
        jstring       jDiskImagePath,
        jstring       jRootfsDiskPath)
{
    if (s_running) {
        LOGE("Already running");
        return JNI_FALSE;
    }

    if (s_stderr_pipe[0] < 0)
        redirect_stderr_to_logcat();

    const char *kernel     = (*env)->GetStringUTFChars(env, jKernelPath,    NULL);
    const char *initrd     = (*env)->GetStringUTFChars(env, jInitrdPath,    NULL);
    const char *sockpth    = (*env)->GetStringUTFChars(env, jSocketPath,    NULL);
    const char *cmdline    = (*env)->GetStringUTFChars(env, jKernelCmdline, NULL);
    const char *diskpath   = jDiskImagePath
                             ? (*env)->GetStringUTFChars(env, jDiskImagePath,   NULL) : NULL;
    const char *rootfspath = jRootfsDiskPath
                             ? (*env)->GetStringUTFChars(env, jRootfsDiskPath,  NULL) : NULL;

    jint nmounts = (*env)->GetArrayLength(env, jVirtfsMounts);

    strncpy(s_socket_path, sockpth, sizeof(s_socket_path) - 1);
    s_socket_path[sizeof(s_socket_path) - 1] = '\0';
    unlink(s_socket_path);

    char chardev_arg[256];
    snprintf(chardev_arg, sizeof(chardev_arg),
             "socket,id=con0,server=on,wait=off,path=%s", sockpth);

    char ram_str[16];
    snprintf(ram_str, sizeof(ram_str), "%dM", (int)ramMb);

    bool has_rootfs = rootfspath && rootfspath[0] != '\0';
    bool has_disk   = diskpath   && diskpath[0]   != '\0';

    char rootfs_drive_arg[512], rootfs_device_arg[64];
    if (has_rootfs) {
        snprintf(rootfs_drive_arg, sizeof(rootfs_drive_arg),
                 "file=%s,format=qcow2,if=none,id=rootfs0,cache=writeback", rootfspath);
        snprintf(rootfs_device_arg, sizeof(rootfs_device_arg),
                 "virtio-blk-device,drive=rootfs0,serial=ociroot");
        LOGI("rootfs image: %s", rootfspath);
    }

    char disk_drive_arg[512], disk_device_arg[64];
    if (has_disk) {
        snprintf(disk_drive_arg, sizeof(disk_drive_arg),
                 "file=%s,format=qcow2,if=none,id=disk0,cache=writeback", diskpath);
        snprintf(disk_device_arg, sizeof(disk_device_arg),
                 "virtio-blk-device,drive=disk0");
        LOGI("data image: %s", diskpath);
    }

    /* SLIRP networking with port forwarding for SSH (2022) and VNC (5901) */
    static const char NETDEV[] =
        "user,id=net0"
        ",hostfwd=tcp:127.0.0.1:2022-:2022"
        ",hostfwd=tcp:127.0.0.1:5901-:5901";

    int max_argc = 27 + nmounts * 4 + (has_rootfs ? 4 : 0) + (has_disk ? 4 : 0);
    QemuArgs *args = malloc(sizeof(QemuArgs));
    args->argv = malloc(sizeof(char *) * (max_argc + 1));
    int i = 0;

#define PUSH(s) args->argv[i++] = strdup(s)
    PUSH("qemu-system-aarch64");
    PUSH("-M");      PUSH("virt");
    PUSH("-accel");  PUSH("tcg");
    PUSH("-cpu");    PUSH("cortex-a57");
    PUSH("-m");      PUSH(ram_str);
    PUSH("-nographic");
    PUSH("-no-reboot");
    // -nographic alone still leaves QEMU's default behavior of layering the interactive
    // monitor onto stdio alongside the serial console. There's no real TTY behind stdio in
    // this embedded pthread (qemu_android_main() runs as a library call inside the app
    // process, not a standalone process launched from a shell) -- the monitor reads EOF
    // from it almost immediately, which qemu_android_main() treats as a quit command,
    // returning and ending the whole VM within under a second of starting. Confirmed live:
    // logcat showed the "(qemu)" monitor prompt print immediately followed by "QEMU thread
    // exited". The monitor isn't used by anything here (control is entirely via the guest
    // console/vsock, not QMP/HMP), so disabling it outright is correct, not just a workaround.
    PUSH("-monitor"); PUSH("none");
    PUSH("-kernel"); PUSH(kernel);
    PUSH("-initrd"); PUSH(initrd);
    PUSH("-append"); PUSH(cmdline);
    PUSH("-chardev"); PUSH(chardev_arg);
    PUSH("-serial");  PUSH("chardev:con0");
    if (has_rootfs) { PUSH("-drive"); PUSH(rootfs_drive_arg); PUSH("-device"); PUSH(rootfs_device_arg); }
    if (has_disk)   { PUSH("-drive"); PUSH(disk_drive_arg);   PUSH("-device"); PUSH(disk_device_arg);   }
    PUSH("-netdev"); PUSH(NETDEV);
    PUSH("-device"); PUSH("virtio-net-device,netdev=net0");
#undef PUSH

    if (diskpath)   (*env)->ReleaseStringUTFChars(env, jDiskImagePath,   diskpath);
    if (rootfspath) (*env)->ReleaseStringUTFChars(env, jRootfsDiskPath,  rootfspath);

    for (jint m = 0; m < nmounts; m++) {
        jstring jmount = (*env)->GetObjectArrayElement(env, jVirtfsMounts, m);
        const char *mount = (*env)->GetStringUTFChars(env, jmount, NULL);

        const char *colon = strchr(mount, ':');
        if (colon && colon > mount) {
            char tag[64];
            size_t tlen = (size_t)(colon - mount);
            if (tlen < sizeof(tag)) {
                memcpy(tag, mount, tlen); tag[tlen] = '\0';
                const char *path = colon + 1;
                char fsdev[640], dev[200];
                snprintf(fsdev, sizeof(fsdev),
                         "local,id=%s,path=%s,security_model=none", tag, path);
                snprintf(dev, sizeof(dev),
                         "virtio-9p-device,fsdev=%s,mount_tag=%s", tag, tag);
                args->argv[i++] = strdup("-fsdev");  args->argv[i++] = strdup(fsdev);
                args->argv[i++] = strdup("-device"); args->argv[i++] = strdup(dev);
                LOGI("virtfs mount: tag=%s path=%s", tag, path);
            }
        }

        (*env)->ReleaseStringUTFChars(env, jmount, mount);
        (*env)->DeleteLocalRef(env, jmount);
    }

    args->argv[i] = NULL;
    args->argc = i;

    (*env)->ReleaseStringUTFChars(env, jKernelPath,    kernel);
    (*env)->ReleaseStringUTFChars(env, jInitrdPath,    initrd);
    (*env)->ReleaseStringUTFChars(env, jSocketPath,    sockpth);
    (*env)->ReleaseStringUTFChars(env, jKernelCmdline, cmdline);

    s_running = true;

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    int rc = pthread_create(&s_thread, &attr, qemu_thread, args);
    pthread_attr_destroy(&attr);

    if (rc != 0) {
        LOGE("pthread_create failed: %s", strerror(rc));
        free_args(args);
        s_running = false;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern void qemu_system_shutdown_request(int cause);

JNIEXPORT void JNICALL
Java_tech_ula_library_qemu_QemuBridge_stop(JNIEnv *env, jobject thiz)
{
    // s_stopping serializes concurrent calls to this function. Without it, two callers can
    // both observe s_running == true and proceed; when the QEMU worker thread exits and
    // broadcasts s_stopped_cond, both wake, both see s_running == false, and both call
    // pthread_join(s_thread, ...) on the same handle. Whichever loses that race joins an
    // already-reaped pthread_t, which bionic treats as a fatal native abort ("invalid
    // pthread_t passed to pthread_join") rather than a catchable error — confirmed live via
    // a tombstone naming the "qemu-stop" thread. This did happen in practice: the Kotlin side
    // used to call QemuService.stopQemu() explicitly and then stopService() (which triggers
    // QemuService.onDestroy(), which also calls stopQemu()), spawning two racing calls here.
    pthread_mutex_lock(&s_mutex);
    if (s_stopping || !s_running) {
        pthread_mutex_unlock(&s_mutex);
        return;
    }
    s_stopping = true;
    pthread_mutex_unlock(&s_mutex);

    LOGI("Requesting QEMU shutdown");
    qemu_system_shutdown_request(4);

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 5;

    bool timed_out = false;
    pthread_mutex_lock(&s_mutex);
    while (s_running) {
        if (pthread_cond_timedwait(&s_stopped_cond, &s_mutex, &ts) == ETIMEDOUT) {
            LOGE("QEMU thread did not exit in 5 s; forcing s_running=false");
            s_running = false;
            timed_out = true;
            break;
        }
    }
    pthread_mutex_unlock(&s_mutex);

    if (!timed_out) {
        pthread_join(s_thread, NULL);
    }

    if (s_socket_path[0]) {
        unlink(s_socket_path);
        LOGI("Removed socket %s", s_socket_path);
        s_socket_path[0] = '\0';
    }

    pthread_mutex_lock(&s_mutex);
    s_stopping = false;
    pthread_mutex_unlock(&s_mutex);
}

JNIEXPORT jboolean JNICALL
Java_tech_ula_library_qemu_QemuBridge_isRunning(JNIEnv *env, jobject thiz)
{
    return s_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_tech_ula_library_qemu_QemuBridge_connectConsole(
        JNIEnv *env, jobject thiz, jstring jSocketPath)
{
    const char *path = (*env)->GetStringUTFChars(env, jSocketPath, NULL);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket(): %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jSocketPath, path);
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        LOGE("connect(%s): %s", path, strerror(errno));
        close(fd);
        fd = -1;
    }

    (*env)->ReleaseStringUTFChars(env, jSocketPath, path);
    return fd;
}

JNIEXPORT jint JNICALL
Java_tech_ula_library_qemu_QemuBridge_readConsole(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray jbuf, jint len)
{
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    int n = (int)read((int)fd, buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_tech_ula_library_qemu_QemuBridge_writeConsole(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray jbuf, jint len)
{
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    int n = (int)write((int)fd, buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_tech_ula_library_qemu_QemuBridge_closeConsole(
        JNIEnv *env, jobject thiz, jint fd)
{
    if (fd >= 0) close((int)fd);
}
