#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>

#if defined(__APPLE__)
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <mach-o/nlist.h>
#endif

/*
 * Native side of dev.mcjava.aot.Compat.
 *
 * The AOT image cannot use LWJGL's SharedLibrary/dlopen path, but the Amethyst
 * host app exports the "pojav*" functions that its custom GLFW needs (as local
 * Mach-O symbols, not in the export trie). So we resolve addresses by walking
 * the loaded images' LC_SYMTAB tables, falling back to dlsym.
 */

#if defined(__APPLE__)
static uintptr_t find_local_symbol(const char *name) {
    uint32_t count = _dyld_image_count();
    for (uint32_t i = 0; i < count; i++) {
        const struct mach_header_64 *hdr = (const struct mach_header_64 *) _dyld_get_image_header(i);
        if (hdr == NULL || hdr->magic != MH_MAGIC_64) {
            continue;
        }
        intptr_t slide = _dyld_get_image_vmaddr_slide(i);
        const uint8_t *base = (const uint8_t *) hdr;
        const struct load_command *lc = (const struct load_command *) (base + sizeof(struct mach_header_64));
        const struct symtab_command *symtab = NULL;
        for (uint32_t c = 0; c < hdr->ncmds; c++) {
            if (lc->cmd == LC_SYMTAB) {
                symtab = (const struct symtab_command *) lc;
                break;
            }
            lc = (const struct load_command *) ((const uint8_t *) lc + lc->cmdsize);
        }
        if (symtab == NULL) {
            continue;
        }
        const struct nlist_64 *syms = (const struct nlist_64 *) (base + symtab->symoff);
        const char *strs = (const char *) (base + symtab->stroff);
        for (uint32_t k = 0; k < symtab->nsyms; k++) {
            uint32_t strx = syms[k].n_un.n_strx;
            if (strx == 0 || strx >= symtab->strsize) {
                continue;
            }
            const char *s = strs + strx;
            if (strcmp(s, name) == 0 || (s[0] == '_' && strcmp(s + 1, name) == 0)) {
                return (uintptr_t) (syms[k].n_value + slide);
            }
        }
    }
    return 0;
}
#endif

static uintptr_t resolve(const char *name) {
#if defined(__APPLE__)
    uintptr_t local = find_local_symbol(name);
    if (local != 0) {
        return local;
    }
#endif
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p == NULL) {
        fprintf(stderr, "[compat] symbol miss: %s\n", name);
    }
    return (uintptr_t) p;
}

JNIEXPORT jlong JNICALL Java_dev_mcjava_aot_Compat_addressOf(JNIEnv *env, jclass cls, jobject provider, jstring name) {
    const char *s = (*env)->GetStringUTFChars(env, name, NULL);
    uintptr_t p = resolve(s);
    (*env)->ReleaseStringUTFChars(env, name, s);
    return (jlong) p;
}

JNIEXPORT jlong JNICALL Java_dev_mcjava_aot_Compat_addressOptional(JNIEnv *env, jclass cls, jobject provider, jstring name) {
    const char *s = (*env)->GetStringUTFChars(env, name, NULL);
    uintptr_t p = resolve(s);
    (*env)->ReleaseStringUTFChars(env, name, s);
    return (jlong) p;
}
