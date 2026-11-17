// libls_spoofer_native.cpp
// 乐跑（com.lptiyu.tanke）原生 shell spoof：拦截 C 级 fopen / __system_property_get。
//
// 背景：libDexHelper 的 maps / 属性读取走 C，不经过 Java，Java 层 hook 拦不住。
// 本 .so 由 LSPosed 通过 assets/native_init 登记并在目标进程 dlopen；
// native_init 里用 LSPosed 官方 ABI（hook_func）对 libc 的 fopen / __system_property_get
// 做内联替换。替换体本身不修改任何数据，只在「路径含 /maps」或「属性命中覆写表」时改值。
//
// 编译（NDK + 项目自带工具链）：
//   在 xposed/build.gradle.kts 的 android{} 里加：
//       ndkVersion = "25.1.8937393"
//       defaultConfig { externalNativeBuild { cmake { cppFlags += "" } } }
//       externalNativeBuild { cmake { path = file("native/CMakeLists.txt") } }
//   并把本文件 + native/CMakeLists.txt 放进来。产物 libls_spoofer_native.so 打进 APK jniLibs。
//   若暂不接 NDK 构建，Kotlin 侧 LepaoDetectionHookerNative.configure 捕获
//   UnsatisfiedLinkError 静默降级，Java 层 13 项检测 hook 不受影响。
//
// 时间线说明：shell 的 native 自检可能在 Application attach 早期就跑完，
// 早于本模块 Java 入口调用 configure()。这种情况下首轮读到的仍是原始值，
// 但 LSPosed 的 Art hook 不会把自身写进 /maps，且黑名单本就没有 LSPosed 特征，
// 因此实际不命中；此 .so 主要覆盖「运行期再读 maps / 属性」的场景（如 4.7 指纹表复核）。

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/types.h>

// ---------- LSPosed native hook ABI（官方 wiki 定义，勿改字段）----------
typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;
typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);

// ---------- MMKV 用户信息导出：在乐跑进程内直接读 MMKV 文件并写到共享目录 ----------
// 为什么在 native 做：乐跑用 VMP 直接调 native MMKV（JNI），不走 Java MMKV API，
// Java 层 hook decodeString 永远不命中（实测 hooks installed:5 但从未触发）；
// 而 LocationSpoofer 在 u0_a66 读不了乐跑（u0_a69）的私有文件。
// 本 .so 运行在乐跑进程内（u0_a69），可直接 fopen 读取。
static const char *MMKV_PATH = "/data/data/com.lptiyu.tanke/files/mmkv/setting";
static const char *EXPORT_PATH = "/sdcard/Download/lepao_user.json";

static long long read_varint(const unsigned char *p, size_t len, size_t *consumed) {
    long long result = 0;
    int shift = 0;
    size_t i = 0;
    while (i < len && shift < 64) {
        unsigned char c = p[i++];
        result |= (long long) (c & 0x7F) << shift;
        if ((c & 0x80) == 0) break;
        shift += 7;
    }
    *consumed = i;
    return result;
}

static int export_lepao_user_from_mmkv(void) {
    FILE *f = fopen(MMKV_PATH, "rb");
    if (f == nullptr) return -1;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > 10 * 1024 * 1024) { fclose(f); return -1; }
    unsigned char *buf = (unsigned char *) malloc((size_t) size);
    if (buf == nullptr) { fclose(f); return -1; }
    size_t rd = fread(buf, 1, (size_t) size, f);
    fclose(f);
    if (rd == 0) { free(buf); return -1; }

    // 顺序扫描 [varint keyLen][key][varint valLen][value]，收集需要的 key
    const char *wanted[] = {
        "user_num", "user_access_token", "refresh_token", "user_nickname",
        "user_name", "user_phone", "user_sex", "school_name", "school_id",
        "auth_num", "role", "role_num", "user_openId", "student_status"
    };
    const int wantedCount = (int) (sizeof(wanted) / sizeof(wanted[0]));

    char json[8192];
    int jpos = 0;
    jpos += snprintf(json + jpos, sizeof(json) - (size_t) jpos, "{");
    int written = 0;

    size_t i = 0;
    while (i + 2 < rd) {
        size_t c1 = 0;
        long long keyLen = read_varint(buf + i, rd - i, &c1);
        if (keyLen <= 0 || keyLen > 64) { i++; continue; }
        size_t keyStart = i + c1;
        if (keyStart + keyLen >= rd) { i++; continue; }
        // key 必须是可打印 ASCII
        int keyOk = 1;
        for (size_t k = 0; k < (size_t) keyLen; k++) {
            unsigned char ch = buf[keyStart + k];
            if (ch < 0x20 || ch > 0x7E) { keyOk = 0; break; }
        }
        if (!keyOk) { i++; continue; }
        char key[65];
        memcpy(key, buf + keyStart, (size_t) keyLen);
        key[keyLen] = 0;

        size_t c2 = 0;
        long long valLen = read_varint(buf + keyStart + keyLen, rd - (keyStart + keyLen), &c2);
        if (valLen < 0 || valLen > 4096) { i++; continue; }
        size_t valStart = keyStart + keyLen + c2;
        if (valStart + valLen > rd) { i++; continue; }

        int match = 0;
        for (int w = 0; w < wantedCount; w++) {
            if (strcmp(key, wanted[w]) == 0) { match = 1; break; }
        }
        if (match && written < 14) {
            // value 必须是可打印文本（跳过二进制值）
            int valOk = 1;
            for (size_t v = 0; v < (size_t) valLen; v++) {
                unsigned char ch = buf[valStart + v];
                if (ch < 0x20 || ch > 0x7E) { valOk = 0; break; }
            }
            if (valOk && valLen > 0) {
                if (written > 0) jpos += snprintf(json + jpos, sizeof(json) - (size_t) jpos, ",");
                jpos += snprintf(json + jpos, sizeof(json) - (size_t) jpos, "\"%s\":\"", key);
                for (size_t v = 0; v < (size_t) valLen && jpos < (int) sizeof(json) - 8; v++) {
                    unsigned char ch = buf[valStart + v];
                    if (ch == '"' || ch == '\\') json[jpos++] = '\\';
                    json[jpos++] = (char) ch;
                }
                json[jpos++] = '"';
                json[jpos] = 0;
                written++;
            }
        }
        i = valStart + (size_t) valLen;
    }

    jpos += snprintf(json + jpos, sizeof(json) - (size_t) jpos, "}\n");
    free(buf);
    if (written == 0) return -2;

    FILE *out = fopen(EXPORT_PATH, "wb");
    if (out == nullptr) return -3;
    fwrite(json, 1, (size_t) jpos, out);
    fclose(out);
    return written;
}

// JNI 入口：Java 侧主动调用导出（也可由 native_init 自行触发）
extern "C"
JNIEXPORT jint JNICALL
Java_com_kdzw8p_mqa_v71f_xposed_LepaoDetectionHookerNative_exportUser(JNIEnv *, jclass) {
    return export_lepao_user_from_mmkv();
}

// ---------- 替换体 ----------
static FILE *(*orig_fopen)(const char *, const char *) = nullptr;
static int (*orig_prop_get)(const char *, char *, int) = nullptr;

// 由 Java 侧 configure 注入两个临时文件路径：
// - maps_redirect_path：Java 层过滤后的 /proc/self/maps 副本文件，C 级
//   fopen 命中 /maps 路径时改道到这个文件（内容 = 壳层黑名单读不到的视图）
// - props_path：属性覆写表文件，行格式 "key=value"，__system_property_get
//   命中时返回表内值，未命中原样放行
char g_maps_redirect_path[512] = {0};
char g_props_override[8192];

// ---------- openat/read 内容替换（A1）：壳若绕过 fopen 直用 openat 读 maps，
// 在 read 时把内容换成已过滤副本 ----------
typedef int (*OpenatFn)(int, const char *, int, ...);
typedef ssize_t (*ReadFn)(int, void *, size_t);
static OpenatFn orig_openat = nullptr;
static ReadFn orig_read = nullptr;
// 记录被改道的 fd（最多 32 个并发，足够覆盖壳的短时读取）
static int g_maps_fds[32] = {0};
static pthread_mutex_t g_fd_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_maps_content[1 << 20];
static size_t g_maps_content_len = 0;
static size_t g_maps_content_pos[32] = {0};

// ---------- /proc/self/status 的 TracerPid 改写 ----------
// 检测方常用 ptrace(PTRACE_TRACEME) 或读 status 的 TracerPid 判断是否有调试器。
// 这里把 status 读出来的内容里的 TracerPid 改成 0，并在 fopen / openat+read 两条路径上都生效。
static char g_status_raw[8192];
static char g_status_content[8192];
static size_t g_status_content_len = 0;
static int g_status_fds[16] = {0};
static size_t g_status_pos[16] = {0};

static void build_status_content() {
    g_status_content_len = 0;
    if (orig_fopen == nullptr) return;
    FILE *f = orig_fopen("/proc/self/status", "r");
    if (f == nullptr) return;
    size_t n = fread(g_status_raw, 1, sizeof(g_status_raw) - 1, f);
    fclose(f);
    if (n == 0) return;
    g_status_raw[n] = 0;
    const char *key = "TracerPid:";
    char *p = strstr(g_status_raw, key);
    if (p == nullptr) {
        memcpy(g_status_content, g_status_raw, n);
        g_status_content[n] = 0;
        g_status_content_len = n;
        return;
    }
    char *eol = strchr(p, '\n');
    size_t tail = eol != nullptr ? (size_t) (eol - g_status_raw) : n;
    size_t head = (size_t) (p - g_status_raw) + strlen(key);
    memcpy(g_status_content, g_status_raw, head);
    size_t out = head;
    g_status_content[out++] = '\t';
    g_status_content[out++] = '0';
    memcpy(g_status_content + out, g_status_raw + tail, n - tail);
    out += n - tail;
    g_status_content[out] = 0;
    g_status_content_len = out;
}

static void remember_status_fd(int fd) {
    pthread_mutex_lock(&g_fd_lock);
    for (int i = 0; i < 16; i++) {
        if (g_status_fds[i] == 0) {
            g_status_fds[i] = fd;
            g_status_pos[i] = 0;
            break;
        }
    }
    pthread_mutex_unlock(&g_fd_lock);
}

static int find_status_fd_slot(int fd) {
    int slot = -1;
    pthread_mutex_lock(&g_fd_lock);
    for (int i = 0; i < 16; i++) {
        if (g_status_fds[i] == fd) {
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_fd_lock);
    return slot;
}

static void forget_status_fd(int fd) {
    pthread_mutex_lock(&g_fd_lock);
    for (int i = 0; i < 16; i++) {
        if (g_status_fds[i] == fd) {
            g_status_fds[i] = 0;
            g_status_pos[i] = 0;
            break;
        }
    }
    pthread_mutex_unlock(&g_fd_lock);
}

static void remember_maps_fd(int fd) {
    pthread_mutex_lock(&g_fd_lock);
    for (int i = 0; i < 32; i++) {
        if (g_maps_fds[i] == 0) {
            g_maps_fds[i] = fd;
            g_maps_content_pos[i] = 0;
            break;
        }
    }
    pthread_mutex_unlock(&g_fd_lock);
}

static int find_maps_fd_slot(int fd) {
    int slot = -1;
    pthread_mutex_lock(&g_fd_lock);
    for (int i = 0; i < 32; i++) {
        if (g_maps_fds[i] == fd) {
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_fd_lock);
    return slot;
}

static void forget_maps_fd(int fd) {
    pthread_mutex_lock(&g_fd_lock);
    for (int i = 0; i < 32; i++) {
        if (g_maps_fds[i] == fd) {
            g_maps_fds[i] = 0;
            g_maps_content_pos[i] = 0;
            break;
        }
    }
    pthread_mutex_unlock(&g_fd_lock);
}

static int hooked_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    int fd = orig_openat(dirfd, path, flags, mode);
    if (fd >= 0 && path != nullptr) {
        if (strstr(path, "/maps") != nullptr && g_maps_content_len > 0) {
            remember_maps_fd(fd);
        } else if (strstr(path, "/status") != nullptr && g_status_content_len > 0) {
            remember_status_fd(fd);
        }
    }
    return fd;
}

static ssize_t hooked_read(int fd, void *buf, size_t count) {
    int statusSlot = find_status_fd_slot(fd);
    if (statusSlot >= 0) {
        size_t remaining = g_status_content_len - g_status_pos[statusSlot];
        if (remaining == 0) {
            forget_status_fd(fd);
            return 0;
        }
        size_t n = count < remaining ? count : remaining;
        memcpy(buf, g_status_content + g_status_pos[statusSlot], n);
        g_status_pos[statusSlot] += n;
        return (ssize_t) n;
    }
    int slot = find_maps_fd_slot(fd);
    if (slot >= 0) {
        size_t remaining = g_maps_content_len - g_maps_content_pos[slot];
        if (remaining == 0) {
            forget_maps_fd(fd);
            return 0; // EOF：壳读到过滤后的末尾
        }
        size_t n = count < remaining ? count : remaining;
        memcpy(buf, g_maps_content + g_maps_content_pos[slot], n);
        g_maps_content_pos[slot] += n;
        return (ssize_t) n;
    }
    return orig_read(fd, buf, count);
}

static void close_maps_fd_on_close(int fd) {
    forget_maps_fd(fd);
}

typedef int (*CloseFn)(int);
static CloseFn orig_close = nullptr;

static int hooked_close(int fd) {
    forget_status_fd(fd);
    forget_maps_fd(fd);
    if (orig_close != nullptr) return orig_close(fd);
    return -1;
}

static FILE *hooked_fopen(const char *path, const char *mode) {
    if (path && g_status_content_len > 0 && strstr(path, "/status") != nullptr) {
        // 用只读内存流返回改写后的 status，调用方 fclose 会正常释放
        return fmemopen(g_status_content, g_status_content_len, "r");
    }
    if (path && g_maps_redirect_path[0] != 0 && strstr(path, "/maps") != nullptr) {
        return orig_fopen(g_maps_redirect_path, mode);
    }
    return orig_fopen(path, mode);
}

// ---------- ptrace：检测常用 PTRACE_TRACEME 判断是否被附加 ----------
typedef long (*PtraceFn)(int, int, void *, void *);
static PtraceFn orig_ptrace = nullptr;

static long hooked_ptrace(int request, int pid, void *addr, void *data) {
    // request 0 = PTRACE_TRACEME。返回 0 让检测认为“成功”，但实际不建立跟踪；
    // 其余请求原样放行，不影响真实调试器与会话。
    if (request == 0) return 0;
    if (orig_ptrace == nullptr) return -1;
    return orig_ptrace(request, pid, addr, data);
}

static int hooked_prop_get(const char *name, char *value, int len) {
    if (name != nullptr && g_props_override[0] != 0) {
        char *p = g_props_override;
        while (p != nullptr && *p != 0) {
            char *nl = strchr(p, '\n');
            size_t lineLen = (nl != nullptr) ? (size_t)(nl - p) : strlen(p);
            char *eq = (char *)memchr(p, '=', lineLen);
            if (eq != nullptr) {
                size_t keyLen = (size_t)(eq - p);
                size_t nameLen = strlen(name);
                if (keyLen == nameLen && strncmp(p, name, keyLen) == 0) {
                    const char *v = eq + 1;
                    size_t vlen = lineLen - keyLen - 1;
                    if (len > 0) {
                        if (vlen + 1 > (size_t)len) vlen = (size_t)len - 1;
                        memcpy(value, v, vlen);
                        value[vlen] = 0;
                    }
                    // bionic 语义：返回属性值长度
                    return (int)vlen;
                }
            }
            if (nl == nullptr) break;
            p = nl + 1;
        }
    }
    return orig_prop_get(name, value, len);
}

// ---------- LSPosed 入口：在 native_init 里挂 libc 的系统库 hook ----------
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (entries == nullptr) return nullptr;
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc == nullptr) libc = RTLD_DEFAULT;

    void *fopen_sym = dlsym(libc, "fopen");
    if (fopen_sym != nullptr) {
        void *backup = nullptr;
        if (entries->hook_func(fopen_sym, (void *)hooked_fopen, &backup) == 0) {
            orig_fopen = (FILE *(*)(const char *, const char *))backup;
        }
    }

    void *prop_sym = dlsym(libc, "__system_property_get");
    if (prop_sym != nullptr) {
        void *backup = nullptr;
        if (entries->hook_func(prop_sym, (void *)hooked_prop_get, &backup) == 0) {
            orig_prop_get = (int (*)(const char *, char *, int))backup;
        }
    }

    // A1：openat + read 组合，覆盖壳绕过 fopen 直用 openat 读 maps 的路径
    void *openat_sym = dlsym(libc, "openat");
    if (openat_sym != nullptr) {
        void *backup = nullptr;
        if (entries->hook_func(openat_sym, (void *)hooked_openat, &backup) == 0) {
            orig_openat = (OpenatFn) backup;
        }
    }
    void *read_sym = dlsym(libc, "read");
    if (read_sym != nullptr) {
        void *backup = nullptr;
        if (entries->hook_func(read_sym, (void *)hooked_read, &backup) == 0) {
            orig_read = (ReadFn) backup;
        }
    }
    void *close_sym = dlsym(libc, "close");
    if (close_sym != nullptr) {
        void *backup = nullptr;
        if (entries->hook_func(close_sym, (void *)hooked_close, &backup) == 0) {
            orig_close = (CloseFn) backup;
        }
    }

    // TracerPid / 调试器检测：ptrace(PTRACE_TRACEME) 直接返回成功
    void *ptrace_sym = dlsym(libc, "ptrace");
    if (ptrace_sym != nullptr) {
        void *backup = nullptr;
        if (entries->hook_func(ptrace_sym, (void *)hooked_ptrace, &backup) == 0) {
            orig_ptrace = (PtraceFn) backup;
        }
    }
    // 在原始 fopen 可用后生成一份 TracerPid=0 的 status 内容
    build_status_content();
    // 不需要 dlopen 回调；返回 nullptr
    return nullptr;
}

// ---------- JNI 桥：Java 侧 LepaoDetectionHookerNative.configure 灌入两张表的路径 ----------
extern "C"
JNIEXPORT void JNICALL
Java_com_kdzw8p_mqa_v71f_xposed_LepaoDetectionHookerNative_nativeConfigure(
        JNIEnv *env, jclass, jstring mapsPath, jstring propsPath) {
    if (mapsPath != nullptr) {
        const char *s = env->GetStringUTFChars(mapsPath, nullptr);
        if (s != nullptr) {
            snprintf(g_maps_redirect_path, sizeof(g_maps_redirect_path), "%s", s);
            // A1：把过滤后的 maps 全文读进内存，供 openat+read 替换用
            FILE *mf = fopen(s, "rb");
            if (mf != nullptr) {
                g_maps_content_len = fread(g_maps_content, 1, sizeof(g_maps_content) - 1, mf);
                g_maps_content[g_maps_content_len] = 0;
                fclose(mf);
            }
            env->ReleaseStringUTFChars(mapsPath, s);
        }
    }
    if (propsPath != nullptr) {
        const char *ps = env->GetStringUTFChars(propsPath, nullptr);
        if (ps != nullptr) {
            FILE *f = fopen(ps, "rb");
            if (f != nullptr) {
                g_props_override[0] = 0;
                if (fread(g_props_override, 1, sizeof(g_props_override) - 1, f) < 0) {
                    g_props_override[0] = 0; // 读失败视为未配置，透传
                }
                fclose(f);
            }
            env->ReleaseStringUTFChars(propsPath, ps);
        }
    }
}
